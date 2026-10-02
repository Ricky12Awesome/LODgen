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
    private final Map<String, Job> automatic = new java.util.concurrent.ConcurrentHashMap<>();
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
        if (closed) throw new IllegalStateException("Server is closing");
        if (command != null && (command.progress.state() == TaskProgress.State.RUNNING || command.progress.state() == TaskProgress.State.PAUSED))
            throw new IllegalStateException("An LODgen task already exists; stop it before starting another");
        boolean dh = RendererSinks.dhAvailable(), voxy = RendererSinks.voxyAvailable() && !server.isDedicatedServer();
        if (area.radius() > area.savedRadius() && !dh && !voxy)
            throw new IllegalStateException("LOD-only generation needs DH or an integrated-server Voxy installation; set saved-radius equal to radius for ordinary pregen");
        if (command != null) command.close();
        var record = new TaskRecord(dimension(level), area, dh, voxy, new TaskProgress(new SquarePlan(area)).snapshot());
        command = new Job(level, record, directory.resolve("task.toml"));
        command.checkpoint();
        LodgenConfig.LOGGER.info("Started {}", status());
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
    private void requireTask() { if (command == null) throw new IllegalStateException("No LODgen task"); }
    public String status() {
        if (command == null) {
            ServerLevel level = server.overworld();
            for (var candidate : server.getAllLevels()) if (!candidate.players().isEmpty()) { level = candidate; break; }
            var job = automatic.get(dimension(level));
            var settings = LodgenConfig.INSTANCE;
            if (job != null && GenerationSettings.policy().extraDhChunks(settings.generationCenter() != GenerationCenter.CURRENT)) return "Automatic " + job.status();
            int radius = settings.generationDistance() > 0 ? settings.generationDistance()
                    : RendererSinks.dhAvailable() ? RendererSinks.dhRadius() : RendererSinks.voxyRadius(level);
            var area = GenerationCenters.automatic(level, radius);
            var throughput = PersistenceRegistry.throughput(level);
            var display = displaySource(level).progress();
            return String.format(java.util.Locale.ROOT, "No command task. Automatic generation %s (%s): dim=%s; center X=%d Z=%d blocks (%s); radius=%dc; saved-radius=%dc; progress=%d chunks completed this session; %.1f chunks/s; ETA=%s",
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
            if (command != null && (command.displayProgress.state() == GenerationProgress.State.RUNNING
                    || command.displayProgress.state() == GenerationProgress.State.WAITING_FOR_RENDERER
                    || command.displayProgress.state() == GenerationProgress.State.PAUSED
                    || System.nanoTime() - command.terminalAt < 5_000_000_000L))
                return new DisplaySource(command.level, () -> command.displayProgress);
        }
        var settings = LodgenConfig.INSTANCE;
        int radius = settings.generationDistance() > 0 ? settings.generationDistance()
                : RendererSinks.dhAvailable() ? RendererSinks.dhRadius() : RendererSinks.voxyRadius(level);
        if (!GenerationSettings.policy().anyAutomatic()) return new DisplaySource(level, () -> new GenerationProgress(radius, -1, GenerationProgress.State.DISABLED));
        if (tasks != null) {
            var job = tasks.automatic.get(dimension(level));
            boolean fixedDh = GenerationSettings.policy().extraDhChunks(settings.generationCenter() != GenerationCenter.CURRENT);
            if (job != null && (fixedDh && job.record.area().radius() == radius
                    || !fixedDh && settings.savedChunkRadius() > radius && job.record.area().radius() == settings.savedChunkRadius()))
                return new DisplaySource(level, () -> job.displayProgress);
        }
        return new DisplaySource(level, () -> {
            var renderer = RendererSinks.progress(level, radius);
            return renderer != null ? renderer : new GenerationProgress(radius, -1, GenerationProgress.State.WAITING_FOR_RENDERER);
        });
    }
    public static boolean commandOverrides(Object level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        GenerationTasks tasks;
        synchronized (GenerationTasks.class) { tasks = SERVERS.get(serverLevel.getServer()); }
        if (tasks != null && tasks.command != null && tasks.command.level == serverLevel) {
            var state = tasks.command.progress.state();
            if (state == TaskProgress.State.RUNNING || state == TaskProgress.State.PAUSED) return true;
        }
        return false;
    }
    public static boolean overridesAutomatic(Object level) {
        return commandOverrides(level) || level instanceof ServerLevel
                && GenerationSettings.policy().extraDhChunks(LodgenConfig.INSTANCE.generationCenter() != GenerationCenter.CURRENT);
    }
    public static void tick(MinecraftServer server) {
        if (server.overworld() == null) return;
        get(server).tick();
    }
    private void tick() {
        if (closed) return;
        if (command != null) command.tick();
        if (!GenerationSettings.policy().automatic()) return;
        for (var level : server.getAllLevels()) {
            if (command != null && command.level == level && (command.progress.state() == TaskProgress.State.RUNNING || command.progress.state() == TaskProgress.State.PAUSED)) continue;
            // Automatic generation follows occupied client dimensions. A headless
            // server may run its configured fixed area in the overworld before login.
            if (level.players().isEmpty() && (!server.isDedicatedServer() || level != server.overworld())) continue;
            var settings = LodgenConfig.INSTANCE;
            boolean fixedDh = GenerationSettings.policy().extraDhChunks(settings.generationCenter() != GenerationCenter.CURRENT);
            if (!fixedDh && settings.savedChunkRadius() == 0) continue;
            if (settings.generationCenter() == GenerationCenter.CURRENT && level.players().isEmpty()) continue;
            int radius = fixedDh ? settings.generationDistance() > 0 ? settings.generationDistance() : RendererSinks.dhRadius() : settings.savedChunkRadius();
            var resolved = GenerationCenters.automatic(level, radius);
            // Automatic following centers change only when crossing a chunk boundary.
            var area = new GenerationArea(resolved.chunkX() * 16, resolved.chunkZ() * 16, radius, resolved.savedRadius());
            String dimension = dimension(level);
            var job = automatic.get(dimension);
            if (job == null || !job.record.area().equals(area)) {
                if (job != null) { job.close(); job.checkpoint(); }
                Path path = directory.resolve("automatic-" + dimension.replaceAll("[^a-zA-Z0-9._-]", "_") + ".toml");
                TaskRecord previous = null;
                try { previous = TaskStore.read(path); }
                catch (Exception error) { LodgenConfig.LOGGER.warn("Cannot restore automatic generation checkpoint", error); }
                var fresh = new TaskRecord(dimension, area, RendererSinks.dhAvailable(), RendererSinks.voxyAvailable() && !server.isDedicatedServer(), new TaskProgress(new SquarePlan(area)).snapshot());
                job = new Job(level, previous != null && previous.area().equals(area) ? previous : fresh, path);
                automatic.put(dimension, job);
            }
            job.tick();
        }
    }
    public static synchronized void beginShutdown(MinecraftServer server) {
        var tasks = SERVERS.get(server);
        if (tasks == null || tasks.closed) return;
        tasks.closed = true;
        if (tasks.command != null) { tasks.command.checkpoint(); tasks.command.close(); }
        for (var job : tasks.automatic.values()) { job.checkpoint(); job.close(); }
    }
    public static synchronized void endShutdown(MinecraftServer server) {
        var tasks = SERVERS.remove(server);
        if (tasks == null) return;
        if (tasks.command != null) tasks.command.checkpoint();
        for (var job : tasks.automatic.values()) job.checkpoint();
        tasks.workers.shutdown();
    }

    private final class Job implements AutoCloseable {
        final ServerLevel level;
        final TaskRecord record;
        final SquarePlan plan;
        final TaskProgress progress;
        final Path path;
        final Map<Long, CompletableFuture<Void>> active = new HashMap<>();
        final ChunkGenerationPipeline pipeline;
        CompletableFuture<Void> surface;
        volatile boolean stopped;
        boolean waiting;
        volatile GenerationProgress displayProgress;
        volatile long terminalAt = Long.MIN_VALUE / 2;
        long lastCheckpoint;
        Job(ServerLevel level, TaskRecord record, Path path) {
            this.level = level; this.record = record; this.path = path;
            plan = new SquarePlan(record.area()); progress = new TaskProgress(plan, record.progress());
            pipeline = new ChunkGenerationPipeline(level);
            publishProgress();
        }
        void tick() {
            if (stopped || closed || progress.state() != TaskProgress.State.RUNNING) return;
            // Completion callbacks refill directly, so the live policy must be
            // checked here as well as on the ordinary server tick.
            if (this != command && (!GenerationSettings.policy().automatic() || overridesByCommand()
                    || !record.area().equals(automaticArea()))) return;
            try { waiting = !RendererSinks.ready(level, record.dh(), record.voxy()); }
            catch (RuntimeException | LinkageError error) {
                progress.fail(rootCause(error)); publishProgress(); checkpoint();
                LodgenConfig.LOGGER.error("Cannot initialize LODgen task output", error);
                return;
            }
            if (waiting) { publishProgress(); return; }
            if (this != command && record.dh() && record.area().radius() > 0 && GenerationSettings.policy().surfaceFirst()) {
                if (surface == null) {
                    surface = RendererSinks.surface(level, record.area(), workers, () -> !stopped && !closed
                            && GenerationSettings.policy().automatic() && !overridesByCommand());
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
                    return RendererSinks.convert(level, lodChunks, workers, record.dh(), record.voxy());
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
        boolean overridesByCommand() {
            return command != null && command.level == level && (command.progress.state() == TaskProgress.State.RUNNING
                    || command.progress.state() == TaskProgress.State.PAUSED);
        }
        GenerationArea automaticArea() {
            var settings = LodgenConfig.INSTANCE;
            boolean lods = GenerationSettings.policy().extraDhChunks(settings.generationCenter() != GenerationCenter.CURRENT);
            int radius = lods ? settings.generationDistance() > 0 ? settings.generationDistance() : RendererSinks.dhRadius() : settings.savedChunkRadius();
            if (radius <= 0) return null;
            var resolved = GenerationCenters.automatic(level, radius);
            return new GenerationArea(resolved.chunkX() * 16, resolved.chunkZ() * 16, radius, resolved.savedRadius());
        }
        void publishProgress() {
            var state = waiting && progress.state() == TaskProgress.State.RUNNING ? GenerationProgress.State.WAITING_FOR_RENDERER
                    : GenerationProgress.State.valueOf(progress.state().name());
            if ((state == GenerationProgress.State.COMPLETE || state == GenerationProgress.State.STOPPED)
                    && (displayProgress == null || displayProgress.state() != state)) terminalAt = System.nanoTime();
            displayProgress = new GenerationProgress(record.area().radius(), plan.chunks() - progress.completedChunks(), state);
        }
        String status() {
            String state = waiting && progress.state() == TaskProgress.State.RUNNING ? "WAITING_FOR_RENDERER" : progress.state().name();
            long done = progress.completedChunks();
            return String.format(java.util.Locale.ROOT,
                    "LODgen %s: dim=%s; center X=%d Z=%d blocks; radius=%dc (%d blocks); saved-radius=%dc (%d blocks); progress=%d/%d chunks (%.2f%%); active=%d; %.1f chunks/s; ETA=%s%s",
                    state, record.dimension(), record.area().blockX(), record.area().blockZ(), record.area().radius(), (long) record.area().radius() * 16,
                    record.area().savedRadius(), (long) record.area().savedRadius() * 16, done, plan.chunks(), done * 100.0 / plan.chunks(), active.size(),
                    PersistenceRegistry.throughput(level).chunksPerSecond(), GenerationProgress.duration(displayProgress.estimatedSeconds(PersistenceRegistry.throughput(level).chunksPerSecond())),
                    progress.error().isEmpty() ? "" : "; error=" + progress.error());
        }
        void checkpoint() {
            if (this != command && automatic.get(record.dimension()) != this) return;
            try {
                TaskStore.write(path, new TaskRecord(record.dimension(), record.area(), record.dh(), record.voxy(), progress.snapshot()));
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
