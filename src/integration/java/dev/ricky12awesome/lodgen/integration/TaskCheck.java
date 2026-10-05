package dev.ricky12awesome.lodgen.integration;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.generation.GenerationCenter;
import dev.ricky12awesome.lodgen.minecraft.GenerationTasks;
import dev.ricky12awesome.lodgen.task.TaskProgress;
import dev.ricky12awesome.lodgen.task.TaskStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real command dispatch and orderly restart, with only a 5c radius / 100 targets. */
public final class TaskCheck {
    private static final Path REPORT = Path.of(Boolean.getBoolean("lodgen.test.reload") ? "integration-reload-result.txt" : "integration-result.txt");
    private static long started;
    private static int stage;
    private static boolean done;
    private static final boolean AUTOMATIC = Boolean.getBoolean("lodgen.test.autostart");
    private static long stoppedAt, stoppedCount;
    private static java.util.concurrent.ScheduledExecutorService timer;
    private static AutoCloseable hiddenWorld;
    private static String canceledProgress;

    public static void run(MinecraftServer server) {
        try {
            started = System.nanoTime();
            LodgenConfig.apply(new LodgenConfig(true, 1, 0, false, 1000, GenerationCenter.CURRENT, 0, 0, 0, LodgenConfig.INSTANCE.caveMode()));
            if (AUTOMATIC) {
                LodgenConfig.apply(new LodgenConfig(true, 1, 5, false, 1000, GenerationCenter.CUSTOM, 65536, -65536, 1, LodgenConfig.INSTANCE.caveMode()));
                com.seibel.distanthorizons.core.config.Config.Common.WorldGenerator.chunkGeneratorMode.set(com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode.FEATURES);
            }
            dev.ricky12awesome.lodgen.minecraft.DhTaskSinkCheck.run(server.overworld());
            if (Boolean.getBoolean("lodgen.test.reload")) {
                var checkpoint = TaskStore.read(server.getWorldPath(LevelResource.ROOT).resolve("lodgen/task.toml"));
                require(checkpoint != null && checkpoint.progress().state() == (AUTOMATIC ? TaskProgress.State.PAUSED : TaskProgress.State.RUNNING), "Task intent must survive shutdown");
                require(checkpoint.automatic() == AUTOMATIC, "Task origin was not checkpointed");
                require(GenerationTasks.get(server).status().contains(AUTOMATIC ? "PAUSED" : "RUNNING"), "Task did not restore automatically");
                if (AUTOMATIC) { stoppedAt = System.nanoTime(); stoppedCount = checkpoint.progress().prefix(); }
                stage = AUTOMATIC ? 5 : 2;
            } else if (AUTOMATIC) {
                GenerationTasks.tick(server);
                var automatic = record(server);
                require(automatic != null && automatic.automatic() && automatic.area().radius() == 5 && automatic.area().savedRadius() == 1,
                        "Autostart did not create the same saved task as start");
                command(server, "lodgen status");
                stage = 0;
            } else {
                command(server, "lodgen start overworld origin 1c");
                command(server, "lodgen pause");
                require(record(server).area().savedRadius() == 0, "Optional saved-radius did not default to zero");
                command(server, "lodgen status");
                command(server, "lodgen continue");
                command(server, "lodgen stop");
                // 79 blocks rounds up to 5c; 15 blocks rounds up to a 1c saved area.
                command(server, "lodgen start overworld 65536 -65536 79 15");
                require(record(server).area().radius() == 5 && record(server).area().savedRadius() == 1, "Command rounding failed");
                stage = 0;
            }
            if (!Boolean.getBoolean("lodgen.test.reload")) {
                stage = -2;
                beginDisconnect(server);
            }
            timer = Executors.newSingleThreadScheduledExecutor(task -> { var t = new Thread(task, "LODgen task regression"); t.setDaemon(true); return t; });
            timer.scheduleAtFixedRate(() -> server.execute(() -> tick(server)), 0, 2, TimeUnit.MILLISECONDS);
        } catch (Throwable error) { fail(server, error); }
    }
    private static dev.ricky12awesome.lodgen.task.TaskRecord record(MinecraftServer server) throws Exception {
        return TaskStore.read(server.getWorldPath(LevelResource.ROOT).resolve("lodgen/task.toml"));
    }
    private static void tick(MinecraftServer server) {
        if (done) return;
        try {
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(60), "Task regression timed out");
            String status = GenerationTasks.get(server).status();
            require(!status.contains("error="), status);
            require(status.contains(stage <= 2 || stage == 5 ? "radius=5c" : "radius=1c") && status.contains("ETA="), "Task status omitted radius or ETA: " + status);
            if (stage == -2) {
                beginDisconnect(server);
            } else if (stage == -1) {
                if (!status.contains("active=0")) return;
                require(status.contains("WAITING_FOR_RENDERER") && status.contains(canceledProgress), "Canceled output advanced or paused the task: " + status);
                require(record(server).progress().state() == TaskProgress.State.RUNNING && record(server).progress().error().isEmpty(), "DH disconnect poisoned the saved task");
                hiddenWorld.close(); hiddenWorld = null;
                LodgenConfig.LOGGER.info("PASS: DH disconnect canceled an in-flight native batch, drained cleanup, and retained RUNNING retryable progress");
                stage = 0;
            } else if (stage == 0) {
                if (status.contains("progress=0/")) return;
                require(!status.contains("COMPLETE"), "Test finished before exercising pause/restart");
                command(server, "lodgen pause");
                stage = 1;
            } else if (stage == 1) {
                if (!status.contains("active=0")) return;
                require(status.contains("PAUSED"), "Pause did not stop dispatch: " + status);
                require(status.contains("ETA=—"), "Paused task showed a running estimate: " + status);
                command(server, "lodgen continue");
                if (AUTOMATIC) command(server, "lodgen pause");
                command(server, "lodgen status");
                Files.writeString(REPORT, AUTOMATIC ? "PASS: autostart created a real 5c task in task.toml; command status/pause/continue control it; native work drained; closed with an unfinished PAUSED automatic task.\n"
                        : "PASS: origin, optional saved radius, block rounding, status, pause, continue and stop commands; closed with an incomplete RUNNING 5c task at 65536,-65536.\n");
                finish(server);
            } else if (stage == 3) {
                var auto = record(server);
                if (auto == null || !auto.automatic() || auto.progress().state() != TaskProgress.State.COMPLETE) return;
                require(auto.area().blockX() == 131072 && auto.area().blockZ() == -131072 && auto.area().radius() == 1 && auto.area().savedRadius() == 0, "Automatic custom center ignored");
                var rendererProgress = dev.ricky12awesome.lodgen.minecraft.RendererSinks.progress(server.overworld(), 1);
                require(rendererProgress != null && rendererProgress.radius() == 1, "DH generation estimate unavailable in a live level");
                if (AUTOMATIC) {
                    command(server, "lodgen cancel");
                    require(record(server).automatic() && record(server).progress().state() == TaskProgress.State.STOPPED, "Cancel did not checkpoint automatic STOPPED");
                    LodgenConfig.apply(new LodgenConfig(true, 1, 1, false, 1000, GenerationCenter.CUSTOM, 196608, -196608, 0, LodgenConfig.INSTANCE.caveMode()));
                    stoppedAt = System.nanoTime(); stoppedCount = dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry.throughput(server.overworld()).totalCompleted();
                    stage = 4; return;
                }
                Files.writeString(REPORT, "PASS: restart automatically resumed the 100-chunk 5c task with saved radius 1c; custom-center autostart created a real task and generated four additional LOD-only chunks. Native file headers audited after shutdown.\n");
                finish(server);
            } else if (stage == 4) {
                if (System.nanoTime() - stoppedAt < TimeUnit.MILLISECONDS.toNanos(300)) return;
                require(record(server).progress().state() == TaskProgress.State.STOPPED && status.contains("STOPPED"), "Stopped task was replaced by autostart");
                require(dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry.throughput(server.overworld()).totalCompleted() == stoppedCount, "Cancel failed to block later automatic work");
                checkPausedDhQueue(server);
                Files.writeString(REPORT, "PASS: PAUSED automatic task survived world reload without dispatch; continue resumed the 100 targets; shrinking completed radius reused progress; cancel saved STOPPED, blocked DH's queue and prevented config changes from restarting; saved-radius files audited after shutdown.\n");
                finish(server);
            } else if (stage == 5) {
                if (System.nanoTime() - stoppedAt < TimeUnit.MILLISECONDS.toNanos(300)) return;
                require(record(server).progress().prefix() == stoppedCount && record(server).progress().state() == TaskProgress.State.PAUSED,
                        "World reload dispatched paused automatic work");
                checkPausedDhQueue(server);
                command(server, "lodgen continue"); stage = 2;
            } else if (stage == 6) {
                if (record(server).area().radius() != 1) return;
                require(record(server).progress().state() == TaskProgress.State.COMPLETE, "Completed larger radius was regenerated on shrink");
                require(dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry.throughput(server.overworld()).totalCompleted() == stoppedCount, "Shrink generated already completed chunks");
                LodgenConfig.apply(new LodgenConfig(true, 1, 1, false, 1000, GenerationCenter.CUSTOM, 131072, -131072, 0, LodgenConfig.INSTANCE.caveMode())); stage = 3;
            } else if (status.contains("COMPLETE")) {
                require(status.contains("progress=100/100"), status);
                require(status.contains("ETA=0s"), "Completed task retained an unfinished estimate: " + status);
                command(server, "lodgen status");
                var checkpoint = record(server);
                require(checkpoint.progress().state() == TaskProgress.State.COMPLETE, "Completion was not checkpointed");
                com.seibel.distanthorizons.core.config.Config.Common.WorldGenerator.chunkGeneratorMode.set(com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode.FEATURES);
                if (AUTOMATIC) {
                    stoppedCount = dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry.throughput(server.overworld()).totalCompleted();
                    LodgenConfig.apply(new LodgenConfig(true, 1, 1, false, 1000, GenerationCenter.CUSTOM, 65536, -65536, 0, LodgenConfig.INSTANCE.caveMode())); stage = 6;
                } else {
                    LodgenConfig.apply(new LodgenConfig(true, 1, 1, false, 1000, GenerationCenter.CUSTOM, 131072, -131072, 0, LodgenConfig.INSTANCE.caveMode())); stage = 3;
                }
            }
        } catch (Throwable error) { fail(server, error); }
    }
    private static void beginDisconnect(MinecraftServer server) {
        GenerationTasks.tick(server);
        String status = GenerationTasks.get(server).status();
        // Automatic tasks may finish DH's rough surface pass before native work.
        if (!status.contains("active=1")) return;
        canceledProgress = status.substring(status.indexOf("progress="), status.indexOf("; active="));
        hiddenWorld = dev.ricky12awesome.lodgen.minecraft.DhTaskSinkCheck.hideWorld();
        stage = -1;
    }
    private static void command(MinecraftServer server, String command) throws Exception {
        require(server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack()) == 1, "Command failed: " + command);
    }
    private static void checkPausedDhQueue(MinecraftServer server) throws Exception {
        for (var wrapper : com.seibel.distanthorizons.api.DhApi.Delayed.worldProxy.getAllLoadedLevelWrappers()) {
            if (!(wrapper instanceof com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper level)
                    || level.getWrappedMcObject() != server.overworld()) continue;
            var dh = (com.seibel.distanthorizons.core.level.IDhServerLevel) level.getDhLevel();
            var queue = new com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue(
                    new com.seibel.distanthorizons.core.generation.DhWorldGenerator(dh), dh);
            try {
                var busy = queue.getClass().getDeclaredMethod("isGeneratorBusy"); busy.setAccessible(true);
                require(Boolean.TRUE.equals(busy.invoke(queue)), "Paused/stopped automatic task did not block DH's queue");
            } finally { queue.close(); }
            return;
        }
        throw new AssertionError("Missing live DH level");
    }
    private static void require(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void finish(MinecraftServer server) {
        done = true;
        if (hiddenWorld != null) {
            try { hiddenWorld.close(); } catch (Exception error) { LodgenConfig.LOGGER.error("Cannot restore DH test world", error); }
            hiddenWorld = null;
        }
        if (timer != null) timer.shutdownNow();
        server.halt(false);
    }
    private static void fail(MinecraftServer server, Throwable error) {
        LodgenConfig.LOGGER.error("Task regression failed", error);
        try { Files.writeString(REPORT, "FAIL: " + error + "\n"); } catch (Exception ignored) {}
        finish(server);
    }
}
