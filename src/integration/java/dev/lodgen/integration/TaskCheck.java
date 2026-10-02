package dev.lodgen.integration;

import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.GenerationCenter;
import dev.lodgen.minecraft.GenerationTasks;
import dev.lodgen.task.TaskProgress;
import dev.lodgen.task.TaskStore;
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
    private static java.util.concurrent.ScheduledExecutorService timer;

    public static void run(MinecraftServer server) {
        try {
            started = System.nanoTime();
            LodgenConfig.apply(new LodgenConfig(true, 1, 0, false, 1000, GenerationCenter.CURRENT, 0, 0, 0));
            // DH owns the budget when installed. Keep this tiny task's admission
            // window at one batch so pausing can leave undispatched work.
            com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.numberOfThreads.set(1);
            require(dev.lodgen.GenerationSettings.current().batches() == 1, "Task regression needs one active batch");
            if (Boolean.getBoolean("lodgen.test.reload")) {
                var checkpoint = TaskStore.read(server.getWorldPath(LevelResource.ROOT).resolve("lodgen/task.toml"));
                require(checkpoint != null && checkpoint.progress().state() == TaskProgress.State.RUNNING, "Running task must survive shutdown");
                require(GenerationTasks.get(server).status().contains("RUNNING"), "Task did not restore automatically");
                stage = 2;
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
            timer = Executors.newSingleThreadScheduledExecutor(task -> { var t = new Thread(task, "LODgen task regression"); t.setDaemon(true); return t; });
            timer.scheduleAtFixedRate(() -> server.execute(() -> tick(server)), 0, 25, TimeUnit.MILLISECONDS);
        } catch (Throwable error) { fail(server, error); }
    }
    private static dev.lodgen.task.TaskRecord record(MinecraftServer server) throws Exception {
        return TaskStore.read(server.getWorldPath(LevelResource.ROOT).resolve("lodgen/task.toml"));
    }
    private static void tick(MinecraftServer server) {
        if (done) return;
        try {
            require(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(60), "Task regression timed out");
            String status = GenerationTasks.get(server).status();
            require(!status.contains("error="), status);
            if (stage == 0) {
                if (status.contains("progress=0/")) return;
                require(!status.contains("COMPLETE"), "Test finished before exercising pause/restart");
                command(server, "lodgen pause");
                stage = 1;
            } else if (stage == 1) {
                if (!status.contains("active=0")) return;
                require(status.contains("PAUSED"), "Pause did not stop dispatch: " + status);
                command(server, "lodgen continue");
                command(server, "lodgen status");
                Files.writeString(REPORT, "PASS: origin, optional saved radius, block rounding, status, pause, continue and stop commands; closed with an incomplete RUNNING 5c task at 65536,-65536.\n");
                finish(server);
            } else if (stage == 3) {
                var auto = TaskStore.read(server.getWorldPath(LevelResource.ROOT).resolve("lodgen/automatic-minecraft_overworld.toml"));
                if (auto == null || auto.progress().state() != TaskProgress.State.COMPLETE) return;
                require(auto.area().blockX() == 131072 && auto.area().blockZ() == -131072 && auto.area().radius() == 1 && auto.area().savedRadius() == 0, "Automatic custom center ignored");
                Files.writeString(REPORT, "PASS: restart automatically resumed the 100-chunk 5c task with saved radius 1c; custom-center automatic DH generated four additional LOD-only chunks. Native file headers audited after shutdown.\n");
                finish(server);
            } else if (status.contains("COMPLETE")) {
                require(status.contains("progress=100/100"), status);
                command(server, "lodgen status");
                var checkpoint = record(server);
                require(checkpoint.progress().state() == TaskProgress.State.COMPLETE, "Completion was not checkpointed");
                com.seibel.distanthorizons.core.config.Config.Common.WorldGenerator.chunkGeneratorMode.set(com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode.FEATURES);
                LodgenConfig.apply(new LodgenConfig(true, 1, 1, false, 1000, GenerationCenter.CUSTOM, 131072, -131072, 0));
                stage = 3;
            }
        } catch (Throwable error) { fail(server, error); }
    }
    private static void command(MinecraftServer server, String command) throws Exception {
        require(server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack()) == 1, "Command failed: " + command);
    }
    private static void require(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    private static void finish(MinecraftServer server) { done = true; if (timer != null) timer.shutdownNow(); server.halt(false); }
    private static void fail(MinecraftServer server, Throwable error) {
        LodgenConfig.LOGGER.error("Task regression failed", error);
        try { Files.writeString(REPORT, "FAIL: " + error + "\n"); } catch (Exception ignored) {}
        finish(server);
    }
}
