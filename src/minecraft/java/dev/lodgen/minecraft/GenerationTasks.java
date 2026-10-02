package dev.lodgen.minecraft;

import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.GenerationArea;
import dev.lodgen.generation.GenerationCenter;
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
    private final Map<String, Job> automatic = new HashMap<>();
    // Voxy and DH consult this from their client/queue threads.
    private volatile Job command;
    private boolean closed;

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
        command.progress.pause(); command.checkpoint();
    }
    public void resume() {
        requireTask(); command.progress.resume(); command.checkpoint();
    }
    public void stop() {
        requireTask(); command.progress.stop(); command.close(); command.checkpoint();
    }
    private void requireTask() { if (command == null) throw new IllegalStateException("No LODgen task"); }
    public String status() {
        if (command == null) {
            for (var job : automatic.values()) return "Automatic " + job.status();
            ServerLevel level = server.overworld();
            for (var candidate : server.getAllLevels()) if (!candidate.players().isEmpty()) { level = candidate; break; }
            var settings = LodgenConfig.INSTANCE;
            int radius = settings.generationDistance() > 0 ? settings.generationDistance()
                    : RendererSinks.dhAvailable() ? RendererSinks.dhRadius() : RendererSinks.voxyRadius(level);
            var area = GenerationCenters.automatic(level, radius);
            var throughput = PersistenceRegistry.throughput(level);
            return String.format(java.util.Locale.ROOT, "No command task. Automatic generation %s: dim=%s; center X=%d Z=%d blocks (%s); radius=%dc; saved-radius=%dc; progress=%d chunks completed this session; %.1f chunks/s",
                    settings.enabled() ? "enabled" : "disabled", dimension(level), area.blockX(), area.blockZ(), settings.generationCenter().name().toLowerCase(java.util.Locale.ROOT),
                    radius, area.savedRadius(), throughput.totalCompleted(), throughput.chunksPerSecond());
        }
        return command.status();
    }
    public static boolean overridesAutomatic(Object level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        GenerationTasks tasks;
        synchronized (GenerationTasks.class) { tasks = SERVERS.get(serverLevel.getServer()); }
        if (tasks != null && tasks.command != null && tasks.command.level == serverLevel) {
            var state = tasks.command.progress.state();
            if (state == TaskProgress.State.RUNNING || state == TaskProgress.State.PAUSED) return true;
        }
        return LodgenConfig.INSTANCE.enabled() && LodgenConfig.INSTANCE.generationCenter() != GenerationCenter.CURRENT
                && RendererSinks.dhAvailable() && DhTaskSink.featuresActive();
    }
    public static void tick(MinecraftServer server) {
        if (server.overworld() == null) return;
        get(server).tick();
    }
    private void tick() {
        if (closed) return;
        if (command != null) command.tick();
        if (!LodgenConfig.INSTANCE.enabled()) return;
        for (var level : server.getAllLevels()) {
            if (command != null && command.level == level && (command.progress.state() == TaskProgress.State.RUNNING || command.progress.state() == TaskProgress.State.PAUSED)) continue;
            // Automatic generation follows occupied client dimensions. A headless
            // server may run its configured fixed area in the overworld before login.
            if (level.players().isEmpty() && (!server.isDedicatedServer() || level != server.overworld())) continue;
            var settings = LodgenConfig.INSTANCE;
            boolean fixedDh = settings.generationCenter() != GenerationCenter.CURRENT && RendererSinks.dhAvailable() && DhTaskSink.featuresActive();
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
        boolean stopped, waiting;
        long lastCheckpoint;
        Job(ServerLevel level, TaskRecord record, Path path) {
            this.level = level; this.record = record; this.path = path;
            plan = new SquarePlan(record.area()); progress = new TaskProgress(plan, record.progress());
            pipeline = new ChunkGenerationPipeline(level);
        }
        void tick() {
            if (stopped || progress.state() != TaskProgress.State.RUNNING) return;
            try { waiting = !RendererSinks.ready(level, record.dh(), record.voxy()); }
            catch (RuntimeException | LinkageError error) {
                progress.fail(rootCause(error)); checkpoint();
                LodgenConfig.LOGGER.error("Cannot initialize LODgen task output", error);
                return;
            }
            if (waiting) return;
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
                    if (progress.state() != TaskProgress.State.RUNNING || System.nanoTime() - lastCheckpoint >= 5_000_000_000L) checkpoint();
                    // Refill as soon as native generation and conversion drain,
                    // rather than leaving the window empty until the next tick.
                    tick();
                }));
            }
        }
        String status() {
            String state = waiting && progress.state() == TaskProgress.State.RUNNING ? "WAITING_FOR_RENDERER" : progress.state().name();
            long done = progress.completedChunks();
            return String.format(java.util.Locale.ROOT,
                    "LODgen %s: dim=%s; center X=%d Z=%d blocks; radius=%dc (%d blocks); saved-radius=%dc (%d blocks); progress=%d/%d chunks (%.2f%%); active=%d; %.1f chunks/s%s",
                    state, record.dimension(), record.area().blockX(), record.area().blockZ(), record.area().radius(), (long) record.area().radius() * 16,
                    record.area().savedRadius(), (long) record.area().savedRadius() * 16, done, plan.chunks(), done * 100.0 / plan.chunks(), active.size(),
                    PersistenceRegistry.throughput(level).chunksPerSecond(), progress.error().isEmpty() ? "" : "; error=" + progress.error());
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
