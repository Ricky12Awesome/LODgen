package dev.ricky12awesome.lodgen.integration;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.generation.GenerationCenter;
import dev.ricky12awesome.lodgen.minecraft.GenerationTasks;
import dev.ricky12awesome.lodgen.task.TaskProgress;
import dev.ricky12awesome.lodgen.task.TaskRecord;
import dev.ricky12awesome.lodgen.task.TaskStore;
import dev.ricky12awesome.lodgen.task.SquarePlan;
import dev.ricky12awesome.lodgen.generation.GenerationArea;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Real command dispatch and orderly restart, with only a 5c radius / 100 targets. */
public final class TaskCheck {
    private static final Path REPORT = Path.of(Boolean.getBoolean("lodgen.test.reload") ? "integration-reload-result.txt" : "integration-result.txt");
    private static long started;
    private static int stage;
    private static boolean done;
    private static final boolean LIFECYCLE_CHECK = Boolean.getBoolean("lodgen.test.autostart");
    private static java.util.concurrent.ScheduledExecutorService timer;
    private static AutoCloseable hiddenWorld;
    private static TaskProgress.Snapshot canceledProgress;

    public static void run(MinecraftServer server) {
        try {
            started = System.nanoTime();
            LodgenConfig.apply(new LodgenConfig(true, 1, 0, false, 1000, GenerationCenter.CURRENT, 0, 0, 0, LodgenConfig.INSTANCE.caveMode()));
            verifyStatus(server);
            if (LIFECYCLE_CHECK) {
                require(server.isDedicatedServer(), "Autostart regression requires a dedicated server");
                LodgenConfig.apply(new LodgenConfig(true, 1, 5, false, 1000, GenerationCenter.CUSTOM,
                        65536, -65536, 1, LodgenConfig.INSTANCE.caveMode()));
                com.seibel.distanthorizons.core.config.Config.Common.WorldGenerator.generatorPlan.set(
                        com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan.CHUNKS_ONLY);
                com.seibel.distanthorizons.core.config.Config.Common.WorldGenerator.chunkGeneratorMode.set(
                        com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode.FEATURES);
                verifyCommands(server);
                GenerationTasks.tick(server);
                require(TaskStore.read(server.getWorldPath(LevelResource.ROOT).resolve("lodgen/task.toml")) == null,
                        "Dedicated server autostart created a task");
                lifecycleCheck(server);
                Files.writeString(REPORT, "PASS: dedicated autostart was blocked; autoResume true/false restored running tasks correctly; paused, stopped and completed checkpoints retained state; manual resume worked.\n");
                finish(server); return;
            }
            dev.ricky12awesome.lodgen.minecraft.DhTaskSinkCheck.run(server.overworld());
            if (Boolean.getBoolean("lodgen.test.reload")) {
                var checkpoint = TaskStore.read(server.getWorldPath(LevelResource.ROOT).resolve("lodgen/task.toml"));
                require(checkpoint != null && checkpoint.progress().state() == TaskProgress.State.RUNNING, "Task intent must survive shutdown");
                require(!checkpoint.automatic(), "Task origin was not checkpointed");
                require(currentProgress(server).state() == TaskProgress.State.RUNNING, "Task did not resume automatically");
                // The minimum load leaves time to pause the first run; the restart only checks completion.
                LodgenConfig.apply(LodgenConfig.SCHEMA.with(LodgenConfig.INSTANCE, "cpuLoad", 3));
                stage = 2;
            } else {
                command(server, "lodgen start overworld origin 1c");
                command(server, "lodgen pause");
                require(record(server).area().savedRadius() == 0, "Optional saved-radius did not default to zero");
                command(server, "lodgen status");
                command(server, "lodgen resume");
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
            var progress = currentProgress(server);
            var display = GenerationTasks.displaySource(server.overworld()).progress();
            int active = activeCount(server);
            require(progress.error().isEmpty(), "Task failed: " + progress.error());
            require(record(server).area().radius() == 5, "Task radius changed");
            if (stage == -2) {
                beginDisconnect(server);
            } else if (stage == -1) {
                if (active != 0) return;
                require(display.state() == dev.ricky12awesome.lodgen.generation.GenerationProgress.State.WAITING_FOR_RENDERER
                        && progress.snapshot().equals(canceledProgress), "Canceled output advanced or paused the task");
                require(record(server).progress().state() == TaskProgress.State.RUNNING, "DH disconnect poisoned the saved task");
                hiddenWorld.close(); hiddenWorld = null;
                LodgenConfig.LOGGER.info("PASS: DH disconnect canceled an in-flight native batch, drained cleanup, and retained RUNNING retryable progress");
                stage = 0;
            } else if (stage == 0) {
                if (progress.completedChunks() == 0) return;
                require(progress.state() == TaskProgress.State.RUNNING, "Test finished before exercising pause/restart");
                command(server, "lodgen pause");
                stage = 1;
            } else if (stage == 1) {
                if (active != 0) return;
                require(progress.state() == TaskProgress.State.PAUSED, "Pause did not stop dispatch");
                require(display.estimatedSeconds(1) < 0, "Paused task showed a running estimate");
                command(server, "lodgen resume");
                command(server, "lodgen status");
                Files.writeString(REPORT, "PASS: origin, optional saved radius, block rounding, status, pause, resume and stop commands; closed with an incomplete RUNNING 5c task at 65536,-65536.\n");
                finish(server);
            } else if (progress.state() == TaskProgress.State.COMPLETE) {
                require(progress.completedChunks() == 100, "Task omitted targets");
                require(display.estimatedSeconds(1) == 0, "Completed task retained an unfinished estimate");
                command(server, "lodgen status");
                require(record(server).progress().state() == TaskProgress.State.COMPLETE, "Completion was not checkpointed");
                LodgenConfig.apply(new LodgenConfig(true, 1, 1, false, 1000, GenerationCenter.CUSTOM, 131072, -131072, 0, LodgenConfig.INSTANCE.caveMode()));
                GenerationTasks.tick(server);
                require(!record(server).automatic() && record(server).area().blockX() == 65536,
                        "Dedicated server replaced the completed task with autostart");
                Files.writeString(REPORT, "PASS: restart resumed the 100-chunk 5c task with saved radius 1c; dedicated-server config changes did not autostart another task. Native file headers audited after shutdown.\n");
                finish(server);
            }
        } catch (Throwable error) { fail(server, error); }
    }
    private static void beginDisconnect(MinecraftServer server) throws Exception {
        GenerationTasks.tick(server);
        if (activeCount(server) != 1) return;
        canceledProgress = currentProgress(server).snapshot();
        hiddenWorld = dev.ricky12awesome.lodgen.minecraft.DhTaskSinkCheck.hideWorld();
        stage = -1;
    }
    private static void command(MinecraftServer server, String command) throws Exception {
        require(server.getCommands().getDispatcher().execute(command, server.createCommandSourceStack()) == 1, "Command failed: " + command);
        verifyStatus(server);
    }
    private static void verifyStatus(MinecraftServer server) {
        var message = GenerationTasks.get(server).statusMessage();
        require(!(message.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents),
                "Status still depends on client translations");
        String text = dev.ricky12awesome.lodgen.minecraft.CommandText.plain(message);
        require(!java.util.regex.Pattern.compile("%(?:[0-9]+\\$)?s|%%").matcher(text).find(),
                "Status contains unformatted placeholders: " + text);
        require(text.contains("Radius:") && text.contains(" blocks)") && text.contains("Caves:")
                && text.contains("Progress:") && text.contains("Chunks/s:") && text.contains("ETA:"),
                "Status omitted generation details: " + text);
    }
    private static void verifyCommands(MinecraftServer server) {
        var root = server.getCommands().getDispatcher().getRoot().getChild("lodgen");
        for (String name : new String[]{"start", "pause", "resume", "stop", "status"})
            require(root != null && root.getChild(name) != null, "Missing command: " + name);
        require(root.getChild("continue") == null && root.getChild("cancel") == null, "Removed commands are still registered");
    }
    private static Object currentJob(MinecraftServer server) throws Exception {
        var field = GenerationTasks.class.getDeclaredField("command");
        field.setAccessible(true);
        var job = field.get(GenerationTasks.get(server));
        require(job != null, "Missing restored task");
        return job;
    }
    private static TaskProgress currentProgress(MinecraftServer server) throws Exception {
        var job = currentJob(server);
        var field = job.getClass().getDeclaredField("progress");
        field.setAccessible(true);
        return (TaskProgress) field.get(job);
    }
    private static int activeCount(MinecraftServer server) throws Exception {
        var job = currentJob(server);
        var field = job.getClass().getDeclaredField("active");
        field.setAccessible(true);
        return ((Map<?, ?>) field.get(job)).size();
    }
    /** Restore real jobs without ticking them, so no terrain is generated by this lifecycle check. */
    private static void lifecycleCheck(MinecraftServer server) throws Exception {
        var path = server.getWorldPath(LevelResource.ROOT).resolve("lodgen/task.toml");
        var config = LodgenConfig.INSTANCE;
        var area = new GenerationArea(65536, -65536, 5, 1);
        var plan = new SquarePlan(area);
        for (boolean autoResume : new boolean[]{true, false}) {
            for (boolean automatic : new boolean[]{false, true}) {
                for (var state : TaskProgress.State.values()) {
                    GenerationTasks.beginShutdown(server);
                    GenerationTasks.endShutdown(server);
                    var saved = state == TaskProgress.State.COMPLETE
                            ? new TaskProgress.Snapshot(state, plan.batches(), List.of(), "")
                            : new TaskProgress.Snapshot(state, 1, List.of(3L), state == TaskProgress.State.PAUSED ? "saved pause detail" : "");
                    var record = new TaskRecord("minecraft:overworld", area, false, false, automatic, saved, config.caveMode());
                    TaskStore.write(path, record);
                    LodgenConfig.apply(LodgenConfig.SCHEMA.with(config, "autoResume", autoResume));
                    var restored = currentProgress(server);
                    var expectedState = state == TaskProgress.State.RUNNING && !autoResume ? TaskProgress.State.PAUSED : state;
                    var expected = new TaskProgress.Snapshot(expectedState, saved.prefix(), saved.beyond(), saved.error());
                    require(restored.snapshot().equals(expected), "Restore changed task intent or progress: " + state + ", autoResume=" + autoResume);
                    var checkpoint = TaskStore.read(path);
                    require(checkpoint.area().equals(area) && checkpoint.automatic() == automatic
                            && checkpoint.progress().prefix() == saved.prefix() && checkpoint.progress().beyond().equals(saved.beyond()),
                            "Restore discarded checkpoint progress or area");
                    if (expectedState == TaskProgress.State.PAUSED) {
                        require(restored.next() == -1, "Restored paused task dispatched work");
                        command(server, "lodgen resume");
                        require(restored.state() == TaskProgress.State.RUNNING && restored.snapshot().prefix() == saved.prefix()
                                && restored.snapshot().beyond().equals(saved.beyond()), "Manual resume discarded progress");
                    }
                    command(server, "lodgen stop");
                }
            }
        }
        LodgenConfig.apply(LodgenConfig.SCHEMA.with(config, "autoResume", false));
        command(server, "lodgen start overworld 65536 -65536 1c 1c");
        require(currentProgress(server).state() == TaskProgress.State.RUNNING, "Auto resume disabled a fresh command task");
        command(server, "lodgen stop");
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
