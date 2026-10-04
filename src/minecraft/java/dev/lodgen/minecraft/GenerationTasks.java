package dev.lodgen.minecraft;

import dev.lodgen.LodgenConfig;
import dev.lodgen.GenerationSettings;
import dev.lodgen.GenerationWorkers;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/** Server-level task selection, command control, autostart, and shutdown. */
public final class GenerationTasks {
    private static final Map<MinecraftServer, GenerationTasks> SERVERS = new IdentityHashMap<>();
    private final MinecraftServer server;
    private final Path directory;
    private final ExecutorService workers;
    // Voxy and DH consult this from their client/queue threads.
    private volatile GenerationJob command;
    private volatile boolean closed;

    public static synchronized GenerationTasks get(MinecraftServer server) {
        return SERVERS.computeIfAbsent(server, GenerationTasks::new);
    }
    private static synchronized GenerationTasks existing(MinecraftServer server) { return SERVERS.get(server); }
    private GenerationTasks(MinecraftServer server) {
        this.server = server;
        directory = server.getWorldPath(LevelResource.ROOT).resolve("lodgen");
        workers = new GenerationWorkers("LODgen task conversion");
        try {
            var record = TaskStore.read(directory.resolve("task.toml"));
            if (record != null) {
                var level = find(record.dimension());
                if (level != null) command = job(level, record);
                else LodgenConfig.LOGGER.error("Cannot resume LODgen: dimension {} is unavailable", record.dimension());
            }
        } catch (Exception invalid) { LodgenConfig.LOGGER.error("Cannot restore LODgen task checkpoint", invalid); }
    }
    private ServerLevel find(String dimension) {
        for (var level : server.getAllLevels()) if (WorldPaths.dimension(level).equals(dimension)) return level;
        return null;
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
            progress = new TaskProgress(plan, new TaskProgress.Snapshot(TaskProgress.State.COMPLETE, plan.batches(), List.of(), ""));
        }
        var record = new TaskRecord(WorldPaths.dimension(level), area, dh, voxy, automatic, progress.snapshot(), LodgenConfig.INSTANCE.caveMode());
        command = job(level, record);
        command.checkpoint();
        LodgenConfig.LOGGER.info("Started {}", command.status());
    }
    private GenerationJob job(ServerLevel level, TaskRecord record) {
        return new GenerationJob(level, record, directory.resolve("task.toml"), workers, () -> closed, job -> command == job);
    }
    public void pause() { requireTask().pause(); }
    public void resume() { requireTask().resume(); }
    public void stop() { requireTask().stop(); }
    private GenerationJob requireTask() {
        autostart();
        if (command == null) throw new IllegalStateException("No LODgen task");
        return command;
    }
    public String status() {
        autostart();
        if (command == null) {
            ServerLevel level = server.overworld();
            for (var candidate : server.getAllLevels()) if (!candidate.players().isEmpty()) { level = candidate; break; }
            var settings = LodgenConfig.INSTANCE;
            int radius = AutomaticSettings.displayRadius(level);
            var area = GenerationCenters.automatic(level, radius);
            var throughput = PersistenceRegistry.throughput(level);
            var display = displaySource(level).progress();
            double rate = throughput.chunksPerSecond();
            return String.format(Locale.ROOT, "No task. Automatic generation %s (%s): dim=%s; center X=%d Z=%d blocks (%s); radius=%dc; saved-radius=%dc; progress=%d chunks completed this session; %.1f chunks/s; ETA=%s",
                    GenerationSettings.policy().anyAutomatic() ? "enabled" : "disabled", display.state(), WorldPaths.dimension(level), area.blockX(), area.blockZ(), settings.generationCenter().name().toLowerCase(Locale.ROOT),
                    radius, area.savedRadius(), throughput.totalCompleted(), rate, GenerationProgress.duration(display.estimatedSeconds(rate)));
        }
        return command.status();
    }

    public record DisplaySource(ServerLevel level, Supplier<GenerationProgress> readProgress) {
        public GenerationProgress progress() { return readProgress.get(); }
    }

    /** Client reads immutable job snapshots, never a live completion tree. A
     * command in another dimension supplies both its own progress and its rate.
     */
    public static DisplaySource displaySource(ServerLevel level) {
        var tasks = existing(level.getServer());
        if (tasks != null) {
            var command = tasks.command;
            if (command != null)
                return new DisplaySource(command.level, () -> command.displayProgress);
        }
        int radius = AutomaticSettings.displayRadius(level);
        if (!GenerationSettings.policy().anyAutomatic()) return new DisplaySource(level, () -> new GenerationProgress(radius, -1, GenerationProgress.State.DISABLED));
        return new DisplaySource(level, () -> {
            var renderer = RendererSinks.progress(level, radius);
            return renderer != null ? renderer : new GenerationProgress(radius, -1, GenerationProgress.State.WAITING_FOR_RENDERER);
        });
    }
    public static boolean commandOverrides(Object level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        var job = commandAt(serverLevel);
        return job != null && job.explicitActive();
    }
    public static boolean overridesAutomatic(Object level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        var job = commandAt(serverLevel);
        if (job == null) return false;
        var policy = GenerationSettings.policy();
        return job.explicitActive() || job.record.automatic() && policy.automatic() && policy.features();
    }
    public static boolean automaticBlocked(Object level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        var tasks = existing(serverLevel.getServer());
        var job = tasks == null ? null : tasks.command;
        if (job == null) return false;
        var state = job.progress.state();
        return tasks.closed || state == TaskProgress.State.STOPPED || state == TaskProgress.State.PAUSED;
    }
    private static GenerationJob commandAt(ServerLevel level) {
        var tasks = existing(level.getServer());
        var job = tasks == null ? null : tasks.command;
        return job != null && job.level == level ? job : null;
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
            if (command.settings.equals(AutomaticSettings.current(command.level))) return;
        }
        for (var level : server.getAllLevels()) {
            if (level.players().isEmpty() && (!server.isDedicatedServer() || level != server.overworld())) continue;
            var settings = LodgenConfig.INSTANCE;
            if (settings.generationCenter() == GenerationCenter.CURRENT && level.players().isEmpty()) continue;
            boolean dh = RendererSinks.dhAvailable() && GenerationSettings.policy().automaticChunks();
            boolean voxy = RendererSinks.voxyAvailable() && !server.isDedicatedServer();
            if (!dh && !voxy && settings.savedChunkRadius() == 0) continue;
            int radius = dh || voxy ? AutomaticSettings.current(level).radius() : settings.savedChunkRadius();
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
}
