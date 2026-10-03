package dev.lodgen.minecraft;

import dev.lodgen.LodgenConfig;
import dev.lodgen.GenerationSettings;
import dev.lodgen.generation.GenerationArea;
import dev.lodgen.generation.GenerationCenter;
import dev.lodgen.generation.GenerationProgress;
import dev.lodgen.task.SquarePlan;
import dev.lodgen.task.TaskProgress;
import dev.lodgen.task.TaskRecord;
import dev.lodgen.task.TaskStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** Server-thread task control, bounded native batches, and world-local restart checkpoints. */
public final class GenerationTasks {
    private static final Map<MinecraftServer, GenerationTasks> SERVERS = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final Path directory;
    private final ExecutorService workers;
    // Voxy and DH consult this from their client/queue threads.
    private volatile Job command;
    private volatile boolean closed;

    public static synchronized GenerationTasks get(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, GenerationTasks::new);
    }
    private GenerationTasks(MinecraftServer server) {
        this.server = server;
        directory = server.getWorldPath(LevelResource.ROOT).resolve("lodgen");
        workers = new dev.lodgen.GenerationWorkers("LODgen task conversion");
        try {
            var record = TaskStore.read(directory.resolve("task.toml"));
            if (record != null) {
                var level = find(record.dimension());
                if (level != null) command = new Job(level, record, directory.resolve("task.toml"));
                else LodgenConfig.LOGGER.error("Cannot resume LODgen: dimension {} is unavailable", record.dimension());
            }
        } catch (Exception invalid) { LodgenConfig.LOGGER.error("Cannot restore LODgen task checkpoint", invalid); }
    }
    private ServerLevel find(String dimension) {
        for (var level : server.getAllLevels()) if (dimension(level).equals(dimension)) return level;
        return null;
    }
    private static String dimension(ServerLevel level) {
        // #if MC_1211
        return level.dimension().location().toString();
        // #else
        return level.dimension().identifier().toString();
        // #endif
    }

    public void start(ServerLevel level, GenerationArea area) {
        start(level, area, false);
    }
    private void start(ServerLevel level, GenerationArea area, boolean automatic) {
        if (closed) throw new IllegalStateException("Server is closing");
        if (command != null && !command.record.automatic()
                && (command.progress.state() == TaskProgress.State.RUNNING || command.progress.state() == TaskProgress.State.PAUSED))
            throw new IllegalStateException("An LODgen task already exists; stop it before starting another");
        boolean dh = RendererSinks.dhAvailable(), voxy = RendererSinks.voxyAvailable() && !server.isDedicatedServer();
        if (area.radius() > area.savedRadius() && !dh && !voxy)
            throw new IllegalStateException("LOD-only generation needs DH or an integrated-server Voxy installation; set saved-radius equal to radius for ordinary pregen");
        if (command != null) command.close();
        var progress = new TaskProgress(new SquarePlan(area));
        // A completed enclosing area needs no regeneration when distance shrinks.
        if (automatic && command != null && command.level == level && command.record.automatic()
                && command.record.caveMode() == LodgenConfig.INSTANCE.caveMode() && command.progress.completedArea(area)) {
            var plan = new SquarePlan(area);
            progress = new TaskProgress(plan, new TaskProgress.Snapshot(TaskProgress.State.COMPLETE, plan.batches(), java.util.List.of(), ""));
        }
        var record = new TaskRecord(dimension(level), area, dh, voxy, automatic, progress.snapshot(), LodgenConfig.INSTANCE.caveMode());
        command = new Job(level, record, directory.resolve("task.toml"));
        command.checkpoint();
        LodgenConfig.LOGGER.info("Started {}", command.status());
    }
    public void pause() {
        requireTask();
        if (command.progress.state() != TaskProgress.State.RUNNING) throw new IllegalStateException("Task is not running");
        command.progress.pause(); command.publishProgress(); command.checkpoint();
    }
    public void resume() {
        requireTask(); command.progress.resume(); command.publishProgress(); command.checkpoint();
    }
    public void stop() {
        requireTask(); command.progress.stop(); command.close(); command.publishProgress(); command.checkpoint();
    }
    private void requireTask() { autostart(); if (command == null) throw new IllegalStateException("No LODgen task"); }
    public String status() {
        autostart();
        if (command == null) {
            ServerLevel level = server.overworld();
            for (var candidate : server.getAllLevels()) if (!candidate.players().isEmpty()) { level = candidate; break; }
            var settings = LodgenConfig.INSTANCE;
            int radius = settings.generationDistance() > 0 ? settings.generationDistance()
                    : RendererSinks.dhAvailable() ? RendererSinks.dhRadius() : RendererSinks.voxyRadius(level);
            var area = GenerationCenters.automatic(level, radius);
            var throughput = PersistenceRegistry.throughput(level);
            var display = displaySource(level).progress();
            return String.format(java.util.Locale.ROOT, "No task. Automatic generation %s (%s): dim=%s; center X=%d Z=%d blocks (%s); radius=%dc; saved-radius=%dc; progress=%d chunks completed this session; %.1f chunks/s; ETA=%s",
                    GenerationSettings.policy().anyAutomatic() ? "enabled" : "disabled", display.state(), dimension(level), area.blockX(), area.blockZ(), settings.generationCenter().name().toLowerCase(java.util.Locale.ROOT),
                    radius, area.savedRadius(), throughput.totalCompleted(), throughput.chunksPerSecond(), GenerationProgress.duration(display.estimatedSeconds(throughput.chunksPerSecond())));
        }
        return command.status();
    }

    public record DisplaySource(ServerLevel level, java.util.function.Supplier<GenerationProgress> readProgress) {
        public GenerationProgress progress() { return readProgress.get(); }
    }

    /** Client reads immutable job snapshots, never a live completion tree. A
     * command in another dimension supplies both its own progress and its rate.
     */
    public static DisplaySource displaySource(ServerLevel level) {
        GenerationTasks tasks;
        synchronized (GenerationTasks.class) { tasks = SERVERS.get(level.getServer()); }
        if (tasks != null) {
            var command = tasks.command;
            if (command != null)
                return new DisplaySource(command.level, () -> command.displayProgress);
        }
        var settings = LodgenConfig.INSTANCE;
        int radius = settings.generationDistance() > 0 ? settings.generationDistance()
                : RendererSinks.dhAvailable() ? RendererSinks.dhRadius() : RendererSinks.voxyRadius(level);
        if (!GenerationSettings.policy().anyAutomatic()) return new DisplaySource(level, () -> new GenerationProgress(radius, -1, GenerationProgress.State.DISABLED));
        return new DisplaySource(level, () -> {
            var renderer = RendererSinks.progress(level, radius);
            return renderer != null ? renderer : new GenerationProgress(radius, -1, GenerationProgress.State.WAITING_FOR_RENDERER);
        });
    }
    public static boolean commandOverrides(Object level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        GenerationTasks tasks;
        synchronized (GenerationTasks.class) { tasks = SERVERS.get(serverLevel.getServer()); }
        if (tasks != null && tasks.command != null && tasks.command.level == serverLevel && !tasks.command.record.automatic()) {
            var state = tasks.command.progress.state();
            if (state == TaskProgress.State.RUNNING || state == TaskProgress.State.PAUSED) return true;
        }
        return false;
    }
    public static boolean overridesAutomatic(Object level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        GenerationTasks tasks;
        synchronized (GenerationTasks.class) { tasks = SERVERS.get(serverLevel.getServer()); }
        if (tasks == null || tasks.command == null || tasks.command.level != serverLevel) return false;
        return commandOverrides(level) || tasks.command.record.automatic()
                && GenerationSettings.policy().automatic() && GenerationSettings.policy().features();
    }
    public static boolean automaticBlocked(Object level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        GenerationTasks tasks;
        synchronized (GenerationTasks.class) { tasks = SERVERS.get(serverLevel.getServer()); }
        if (tasks == null || tasks.command == null) return false;
        var state = tasks.command.progress.state();
        return tasks.closed || state == TaskProgress.State.STOPPED || state == TaskProgress.State.PAUSED;
    }
    public static void tick(MinecraftServer server) {
        if (server.overworld() == null) return;
        get(server).tick();
    }
    private void tick() {
        if (closed) return;
        autostart();
        if (command != null) command.tick();
    }
    /** Use the exact command job/checkpoint/pipeline. Current position is
     * captured once, just like /lodgen start ... current. Paused/stopped jobs
     * are never replaced by login, config changes or completion callbacks.
     */
    private void autostart() {
        if (closed || !GenerationSettings.policy().automatic()) return;
        if (command != null) {
            var state = command.progress.state();
            if (state == TaskProgress.State.PAUSED || state == TaskProgress.State.STOPPED) return;
            if (!command.record.automatic() && state == TaskProgress.State.RUNNING) return;
            if (command.settings.equals(AutoSettings.current(command.level))) return;
        }
        for (var level : server.getAllLevels()) {
            if (level.players().isEmpty() && (!server.isDedicatedServer() || level != server.overworld())) continue;
            var settings = LodgenConfig.INSTANCE;
            if (settings.generationCenter() == GenerationCenter.CURRENT && level.players().isEmpty()) continue;
            boolean dh = RendererSinks.dhAvailable() && GenerationSettings.policy().automaticChunks();
            boolean voxy = RendererSinks.voxyAvailable() && !server.isDedicatedServer();
            if (!dh && !voxy && settings.savedChunkRadius() == 0) continue;
            int radius = dh || voxy ? AutoSettings.current(level).radius() : settings.savedChunkRadius();
            if (radius <= 0) continue; // Voxy may not have attached its engine yet.
            var resolved = GenerationCenters.automatic(level, radius);
            if (command != null && command.record.automatic() && command.settings.center() == GenerationCenter.CURRENT
                    && settings.generationCenter() == GenerationCenter.CURRENT) {
                resolved = new GenerationArea(command.record.area().blockX(), command.record.area().blockZ(), radius, settings.savedChunkRadius());
            }
            start(level, resolved, true);
            return;
        }
    }
    private record AutoSettings(GenerationCenter center, int x, int z, int radius, int savedRadius, dev.lodgen.generation.CaveMode caveMode) {
        static AutoSettings current(ServerLevel level) {
            var config = LodgenConfig.INSTANCE;
            var center = GenerationCenters.resolve(level, 0, 0);
            boolean lods = RendererSinks.dhAvailable() && GenerationSettings.policy().features()
                    || RendererSinks.voxyAvailable() && !level.getServer().isDedicatedServer();
            int radius = !lods ? config.savedChunkRadius() : config.generationDistance() > 0 ? config.generationDistance()
                    : RendererSinks.dhAvailable() ? RendererSinks.dhRadius() : RendererSinks.voxyRadius(level);
            return new AutoSettings(config.generationCenter(), center.getX(), center.getZ(), radius, config.savedChunkRadius(), config.caveMode());
        }
    }
    public static synchronized void beginShutdown(MinecraftServer server) {
        var tasks = SERVERS.get(server);
        if (tasks == null || tasks.closed) return;
        tasks.closed = true;
        if (tasks.command != null) { tasks.command.checkpoint(); tasks.command.close(); }
    }
    public static synchronized void endShutdown(MinecraftServer server) {
        var tasks = SERVERS.remove(server);
        if (tasks == null) return;
        if (tasks.command != null) tasks.command.checkpoint();
        tasks.workers.shutdown();
    }

    private final class Job implements AutoCloseable {
        final ServerLevel level;
        final TaskRecord record;
        final SquarePlan plan;
        final TaskProgress progress;
        final Path path;
        final AutoSettings settings;
        final Map<Long, CompletableFuture<Void>> active = new HashMap<>();
        final ChunkGenerationPipeline pipeline;
        CompletableFuture<Void> surface;
        volatile boolean stopped;
        boolean waiting;
        volatile GenerationProgress displayProgress;
        long lastCheckpoint;
        Job(ServerLevel level, TaskRecord record, Path path) {
            this.level = level; this.record = record; this.path = path;
            var live = AutoSettings.current(level);
            settings = record.automatic() ? new AutoSettings(live.center(), live.center() == GenerationCenter.CURRENT ? 0 : record.area().blockX(),
                    live.center() == GenerationCenter.CURRENT ? 0 : record.area().blockZ(), record.area().radius(), record.area().savedRadius(), record.caveMode()) : live;
            plan = new SquarePlan(record.area()); progress = new TaskProgress(plan, record.progress());
            pipeline = new ChunkGenerationPipeline(level, record.caveMode());
            publishProgress();
        }
        void tick() {
            if (stopped || closed || progress.state() != TaskProgress.State.RUNNING) return;
            // Completion callbacks refill directly, so the live policy must be
            // checked here as well as on the ordinary server tick.
            if (record.automatic() && !automaticAllowed()) { publishProgress(); return; }
            if (record.automatic() && !settings.equals(AutoSettings.current(level))) return;
            try { waiting = !RendererSinks.ready(level, record.dh(), record.voxy()); }
            catch (RuntimeException | LinkageError error) {
                progress.fail(rootCause(error)); publishProgress(); checkpoint();
                LodgenConfig.LOGGER.error("Cannot initialize LODgen task output", error);
                return;
            }
            if (waiting) { publishProgress(); return; }
            if (record.automatic() && record.dh() && record.area().radius() > 0 && GenerationSettings.policy().surfaceFirst()) {
                if (surface == null) {
                    surface = RendererSinks.surface(level, record.area(), workers, () -> !stopped && !closed
                            && this == command && progress.state() == TaskProgress.State.RUNNING && automaticAllowed());
                    surface.whenComplete((ignored, error) -> server.execute(() -> {
                        if (error != null && cause(error) instanceof java.util.concurrent.CancellationException) surface = null;
                        else if (error != null && !stopped && !closed) {
                            progress.fail(rootCause(error)); publishProgress(); checkpoint();
                            LodgenConfig.LOGGER.error("Automatic DH surface generation failed", error);
                        } else tick();
                    }));
                }
                if (surface == null || !surface.isDone() || surface.isCompletedExceptionally()) return;
            }
            int limit = dev.lodgen.GenerationSettings.current().batches();
            for (int budget = 0; budget < 256 && active.size() < limit; budget++) {
                long index = progress.next();
                if (index < 0) break;
                if (active.containsKey(index)) continue;
                var batch = plan.batch(index);
                // Conversion and completion are observed on the server thread, without blocking ticks.
                var future = pipeline.generate(batch.x(), batch.z(), batch.width(), batch.height(), record.area(), workers, nativeBatch -> {
                    var lodChunks = nativeBatch.chunks.stream().filter(chunk -> record.area().lods(PersistenceRegistry.x(chunk.getPos()), PersistenceRegistry.z(chunk.getPos()))).toList();
                    return RendererSinks.convert(level, nativeBatch.sourceLevel(), lodChunks, workers, record.dh(), record.voxy());
                });
                active.put(index, future);
                future.whenComplete((ignored, error) -> server.execute(() -> {
                    active.remove(index);
                    if (error == null) progress.complete(index);
                    else if (!stopped && !closed && !(cause(error) instanceof java.util.concurrent.CancellationException)) {
                        progress.fail(rootCause(error));
                        LodgenConfig.LOGGER.error("LODgen task paused at {},{}", batch.x(), batch.z(), error);
                    }
                    publishProgress();
                    if (progress.state() != TaskProgress.State.RUNNING || System.nanoTime() - lastCheckpoint >= 5_000_000_000L) checkpoint();
                    // Refill as soon as native generation and conversion drain,
                    // rather than leaving the window empty until the next tick.
                    tick();
                }));
            }
            publishProgress();
        }
        boolean automaticAllowed() {
            return GenerationSettings.policy().automatic() && (!record.dh() || GenerationSettings.policy().features()
                    || record.area().savedRadius() >= record.area().radius());
        }
        void publishProgress() {
            var state = record.automatic() && progress.state() == TaskProgress.State.RUNNING && !automaticAllowed() ? GenerationProgress.State.DISABLED
                    : waiting && progress.state() == TaskProgress.State.RUNNING ? GenerationProgress.State.WAITING_FOR_RENDERER
                    : GenerationProgress.State.valueOf(progress.state().name());
            displayProgress = new GenerationProgress(record.area().radius(), plan.chunks() - progress.completedChunks(), state);
        }
        String status() {
            String state = displayProgress.state().name();
            long done = progress.completedChunks();
            return String.format(java.util.Locale.ROOT,
                    "LODgen %s%s: dim=%s; center X=%d Z=%d blocks; radius=%dc (%d blocks); saved-radius=%dc (%d blocks); caves=%s; progress=%d/%d chunks (%.2f%%); active=%d; %.1f chunks/s; ETA=%s%s",
                    record.automatic() ? "automatic " : "", state, record.dimension(), record.area().blockX(), record.area().blockZ(), record.area().radius(), (long) record.area().radius() * 16,
                    record.area().savedRadius(), (long) record.area().savedRadius() * 16, record.caveMode().name().toLowerCase(java.util.Locale.ROOT), done, plan.chunks(), done * 100.0 / plan.chunks(), active.size(),
                    PersistenceRegistry.throughput(level).chunksPerSecond(), GenerationProgress.duration(displayProgress.estimatedSeconds(PersistenceRegistry.throughput(level).chunksPerSecond())),
                    progress.error().isEmpty() ? "" : "; error=" + progress.error());
        }
        void checkpoint() {
            if (this != command) return;
            try {
                TaskStore.write(path, new TaskRecord(record.dimension(), record.area(), record.dh(), record.voxy(), record.automatic(), progress.snapshot(), record.caveMode()));
                lastCheckpoint = System.nanoTime();
            } catch (Exception error) { LodgenConfig.LOGGER.error("Cannot checkpoint LODgen task at {}", path, error); }
        }
        @Override public void close() { stopped = true; pipeline.close(); }
    }
    private static Throwable cause(Throwable error) {
        while (error.getCause() != null) error = error.getCause();
        return error;
    }
    private static String rootCause(Throwable error) { return cause(error).toString(); }
}
