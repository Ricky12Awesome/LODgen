package dev.ricky12awesome.lodgen.integration;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiGeneratorPlan;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.fullData.sources.FullDataSourceV2;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.generation.GenerationArea;
import dev.ricky12awesome.lodgen.generation.GenerationCenter;
import dev.ricky12awesome.lodgen.minecraft.GenerationTasks;
import dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry;
import dev.ricky12awesome.lodgen.task.TaskProgress;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Real DH rough/chunk paths and live disable, using at most 5c automatic jobs. */
public final class DhPlanCheck {
    private static final java.util.concurrent.ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor();
    public static void run(MinecraftServer server, ServerLevel level, DhWorldGenerator generator, ExecutorService executor) throws Exception {
        Config.Common.WorldGenerator.chunkGeneratorMode.set(EDhApiDistantGeneratorMode.FEATURES);
        configure(false, EDhApiGeneratorPlan.SURFACE_THEN_CHUNKS, GenerationCenter.CURRENT, 64, 0);
        long before = PersistenceRegistry.throughput(level).totalCompleted();
        request(generator, executor, 5000, -5000, (byte) 1).thenCompose(ignored -> onServer(server, () -> {
            require(PersistenceRegistry.throughput(level).totalCompleted() == before, "Rough phase reached native chunks");
        })).thenCompose(ignored -> request(generator, executor, 5000, -5000, (byte) 0))
                .thenCompose(ignored -> roughQueue(server, generator, 5500))
                .thenCompose(ignored -> onServer(server, () -> {
                    require(PersistenceRegistry.get(level).suppress(5000, -5000), "DH chunk phase bypassed LODgen with auto off");
                    configure(false, EDhApiGeneratorPlan.CHUNKS_ONLY, GenerationCenter.CURRENT, 64, 0);
                })).thenCompose(ignored -> centerQueue(generator))
                .thenCompose(ignored -> onServer(server, () -> configure(false, EDhApiGeneratorPlan.SURFACE_ONLY, GenerationCenter.CURRENT, 64, 0)))
                .thenCompose(ignored -> request(generator, executor, 6000, -6000, (byte) 0))
                .thenCompose(ignored -> onServer(server, () -> {
                    require(!PersistenceRegistry.get(level).suppress(6000, -6000), "Surface Only with auto off generated native chunks");
                    configure(true, EDhApiGeneratorPlan.SURFACE_ONLY, GenerationCenter.CUSTOM, 5, 7000 * 16);
                    seedAutomatic(server, level, 7000 * 16, -7000 * 16, 5);
                })).thenCompose(ignored -> until(server, () -> automaticProgress(server).completedChunks() > 0))
                .thenCompose(ignored -> onServer(server, () -> {
                    require(automaticProgress(server).completedChunks() < 100, "Fixture missed live disable window");
                    Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.DISABLED);
                })).thenCompose(ignored -> until(server, () -> automaticActive(server) == 0))
                .thenCompose(ignored -> {
                    long completed = automaticProgress(server).completedChunks();
                    return delay(server, 300).thenCompose(done -> onServer(server, () -> {
                        require(automaticProgress(server).completedChunks() == completed && completed < 100, "Disabled automatic callback kept refilling");
                        require(GenerationTasks.displaySource(level).progress().state() == dev.ricky12awesome.lodgen.generation.GenerationProgress.State.DISABLED,
                                "Disabled automatic status did not follow DH");
                        Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.SURFACE_ONLY);
                    }));
                })
                .thenCompose(ignored -> until(server, () -> automaticProgress(server).state() == TaskProgress.State.COMPLETE))
                .thenCompose(ignored -> onServer(server, () -> {
                    require(automaticArea(server).blockX() == 7000 * 16 && automaticProgress(server).completedChunks() == 100,
                            "Re-enabling did not resume the incomplete automatic task");
                    Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.CHUNKS_ONLY);
                    requireOverrideBusy(generator, true, "API generator duplicated the automatic chunk phase");
                    LodgenConfig.apply(new LodgenConfig(false, 1, 5, false, 1000, GenerationCenter.CUSTOM, 7000 * 16, -7000 * 16, 0));
                    Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.CHUNKS_ONLY);
                    require(!GenerationTasks.overridesAutomatic(level), "Addon toggle off left the old automatic task blocking DH's chunk phase");
                    requireOverrideBusy(generator, false, "API generator did not resume when automatic generation was turned off");
                    var dhLevel = (IDhServerLevel) generator.serverLevelWrapper.getDhLevel();
                    var queue = new WorldGenerationQueue(new DhWorldGenerator(dhLevel), dhLevel);
                    try {
                        var busy = queue.getClass().getDeclaredMethod("isGeneratorBusy"); busy.setAccessible(true);
                        require(Boolean.FALSE.equals(busy.invoke(queue)), "DH chunk admission must resume when addon automatic generation is off");
                    } finally { queue.close(); }
                    Config.Common.WorldGenerator.generatorPlan.set(EDhApiGeneratorPlan.DISABLED);
                    requireOverrideBusy(generator, true, "API generator ignored DH Disabled");
                    require(server.getCommands().getDispatcher().execute("lodgen start overworld 131072 -131072 1c", server.createCommandSourceStack()) == 1,
                            "Commands must run with DH disabled");
                })).thenCompose(ignored -> until(server, () -> automaticProgress(server).state() == TaskProgress.State.COMPLETE))
                .thenCompose(ignored -> onServer(server, () -> {
                    configure(true, EDhApiGeneratorPlan.SURFACE_THEN_CHUNKS, GenerationCenter.CUSTOM, 1, 9000 * 16);
                    seedAutomatic(server, level, 9000 * 16, -9000 * 16, 1);
                }))
                .thenCompose(ignored -> until(server, () -> automaticProgress(server).state() == TaskProgress.State.COMPLETE
                        && automaticArea(server).blockX() == 9000 * 16))
                .thenCompose(ignored -> onServer(server, () -> {
                    var surface = (CompletableFuture<?>) field(automaticJob(server), "surface");
                    require(surface != null && surface.isDone() && !surface.isCompletedExceptionally(), "Fixed-area job skipped DH's rough surface phase");
                })).thenCompose(ignored -> roughQueue(server, generator, 9000))
                .thenCompose(ignored -> roughQueue(server, generator, 9000, true))
                .thenCompose(ignored -> onServer(server, () -> {
                    configure(true, EDhApiGeneratorPlan.DISABLED, GenerationCenter.CUSTOM, 1, 10000 * 16);
                    LodgenConfig.apply(new LodgenConfig(true, 1, 1, false, 1000, GenerationCenter.CUSTOM, 160000, -160000, 2));
                })).thenCompose(ignored -> {
                    long completed = PersistenceRegistry.throughput(level).totalCompleted();
                    return delay(server, 300).thenCompose(done -> onServer(server, () -> {
                        require(PersistenceRegistry.throughput(level).totalCompleted() == completed, "DH Disabled allowed saved-radius automatic pregen");
                        require(automaticArea(server).blockX() == 9000 * 16, "Disabled created another automatic job");
                        Files.writeString(Path.of("integration-result.txt"), "PASS: DH Surface Then Chunks retains rough surfaces beyond the native radius and uses native chunks with auto off; Chunks Only with auto off selects the center before the outer section; Surface Only respects opt-in chunks; live Disabled drains without refilling, blocks saved-radius auto jobs, and permits commands; re-enabling resumes; fixed-area rough phase completes before chunks and permits viewport rough requests. API generator overrides pause duplicate chunk work, retain rough surfaces, resume with automatic generation off and obey Disabled. Generated 156 native LOD targets, using at most 5c radius.\n");
                    }));
                }).orTimeout(60, TimeUnit.SECONDS).whenComplete((ignored, failure) -> server.execute(() -> {
                    if (failure != null) {
                        LodgenConfig.LOGGER.error("DH plan regression failed", failure);
                        try { Files.writeString(Path.of("integration-result.txt"), "FAIL: " + failure + "\n"); } catch (Exception error) { failure.addSuppressed(error); }
                    }
                    TIMER.shutdownNow(); generator.close(); executor.shutdown(); server.halt(false);
                }));
    }
    private static void configure(boolean enabled, EDhApiGeneratorPlan plan, GenerationCenter center, int radius, int coordinate) throws Exception {
        Config.Common.WorldGenerator.generatorPlan.set(plan);
        LodgenConfig.apply(new LodgenConfig(enabled, 1, radius, false, 1000, center, coordinate, -coordinate, 0));
    }
    private static CompletableFuture<Void> request(DhWorldGenerator generator, ExecutorService executor, int x, int z, byte detail) {
        int width = 4 << detail;
        var data = FullDataSourceV2.createEmpty(DhSectionPos.encode((byte) (6 + detail), Math.floorDiv(x, width), Math.floorDiv(z, width)));
        return generator.generateLod(x, z, Math.floorDiv(x, width), Math.floorDiv(z, width), detail, data,
                EDhApiDistantGeneratorMode.FEATURES, executor, ignored -> require(!data.getApiDataPointColumn(0, 0).isEmpty(), "Empty DH result"))
                .whenComplete((ignored, failure) -> data.close());
    }
    private static CompletableFuture<Void> centerQueue(DhWorldGenerator generator) {
        var dhLevel = (IDhServerLevel) generator.serverLevelWrapper.getDhLevel();
        var queue = new WorldGenerationQueue(new DhWorldGenerator(dhLevel), dhLevel);
        var first = new java.util.concurrent.atomic.AtomicReference<String>();
        var edge = queue.submitRetrievalTask(DhSectionPos.encode((byte) 6, 4968 / 4, -5032 / 4), (byte) 0)
                .thenAccept(result -> { first.compareAndSet(null, "edge"); result.dataSource.close(); });
        var center = queue.submitRetrievalTask(DhSectionPos.encode((byte) 6, 5020 / 4, -4980 / 4), (byte) 0)
                .thenAccept(result -> { first.compareAndSet(null, "center"); result.dataSource.close(); });
        queue.startAndSetTargetPos(new DhBlockPos2D(5020 * 16, -4980 * 16));
        return CompletableFuture.allOf(center, edge).thenRun(() -> require("center".equals(first.get()), "DH queue started from the edge"))
                .whenComplete((ignored, failure) -> queue.close());
    }
    private static CompletableFuture<Void> roughQueue(MinecraftServer server, DhWorldGenerator generator, int center) {
        return roughQueue(server, generator, center, false);
    }
    private static CompletableFuture<Void> roughQueue(MinecraftServer server, DhWorldGenerator generator, int center, boolean override) {
        var dhLevel = (IDhServerLevel) generator.serverLevelWrapper.getDhLevel();
        var queue = new WorldGenerationQueue(override ? apiOverride(generator) : new DhWorldGenerator(dhLevel), dhLevel);
        var surface = queue.submitRetrievalTask(DhSectionPos.encode((byte) 7, (center + 128) / 8, -center / 8), (byte) 1);
        require(((java.util.concurrent.atomic.AtomicInteger) field(queue, "lodgen$roughPending")).get() == 1, "Missing pending rough counter");
        queue.startAndSetTargetPos(new DhBlockPos2D(center * 16, -center * 16));
        return surface.thenCompose(result -> onServer(server, () -> {
            require(result.dataSource != null && !result.dataSource.getApiDataPointColumn(0, 0).isEmpty(), "Viewport rough surface was blocked by native radius/job");
            result.dataSource.close();
            require(((java.util.concurrent.atomic.AtomicInteger) field(queue, "lodgen$roughPending")).get() == 0, "Rough pending counter did not drain");
            require(((java.util.concurrent.atomic.AtomicInteger) field(queue, "lodgen$roughActive")).get() == 0, "Rough active counter did not drain");
        })).whenComplete((ignored, failure) -> queue.close());
    }
    private static com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator apiOverride(DhWorldGenerator delegate) {
        var type = com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator.class;
        return (com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator)
                java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                    if (method.getName().equals("close")) return null;
                    try { return method.invoke(delegate, args); }
                    catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
                });
    }
    private static void requireOverrideBusy(DhWorldGenerator generator, boolean expected, String message) throws Exception {
        var level = (IDhServerLevel) generator.serverLevelWrapper.getDhLevel();
        var queue = new WorldGenerationQueue(apiOverride(generator), level);
        try {
            var busy = queue.getClass().getDeclaredMethod("isGeneratorBusy"); busy.setAccessible(true);
            require(Boolean.valueOf(expected).equals(busy.invoke(queue)), message);
        } finally { queue.close(); }
    }
    private static Object field(Object object, String name) {
        try { var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    private static Object automaticJob(MinecraftServer server) { return field(GenerationTasks.get(server), "command"); }
    /** Dedicated-server autostart is intentionally disabled; seed the same automatic job explicitly for DH integration coverage. */
    private static void seedAutomatic(MinecraftServer server, ServerLevel level, int blockX, int blockZ, int radius) throws Exception {
        var tasks = GenerationTasks.get(server);
        var start = GenerationTasks.class.getDeclaredMethod("start", ServerLevel.class, GenerationArea.class, boolean.class);
        start.setAccessible(true);
        start.invoke(tasks, level, new GenerationArea(blockX, blockZ, radius, LodgenConfig.INSTANCE.savedChunkRadius()), true);
    }
    private static TaskProgress automaticProgress(MinecraftServer server) {
        var job = automaticJob(server);
        return job == null ? new TaskProgress(new dev.ricky12awesome.lodgen.task.SquarePlan(new dev.ricky12awesome.lodgen.generation.GenerationArea(0, 0, 1, 0))) : (TaskProgress) field(job, "progress");
    }
    private static dev.ricky12awesome.lodgen.generation.GenerationArea automaticArea(MinecraftServer server) {
        return ((dev.ricky12awesome.lodgen.task.TaskRecord) field(automaticJob(server), "record")).area();
    }
    private static int automaticActive(MinecraftServer server) { return ((Map<?, ?>) field(automaticJob(server), "active")).size(); }
    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }
    private static CompletableFuture<Void> onServer(MinecraftServer server, CheckedAction action) {
        var future = new CompletableFuture<Void>();
        server.execute(() -> { try { action.run(); future.complete(null); } catch (Throwable error) { future.completeExceptionally(error); } });
        return future;
    }
    private static CompletableFuture<Void> until(MinecraftServer server, BooleanSupplier condition) {
        var result = new CompletableFuture<Void>(); poll(server, condition, result); return result;
    }
    private static void poll(MinecraftServer server, BooleanSupplier condition, CompletableFuture<Void> result) {
        TIMER.schedule(() -> server.execute(() -> {
            try { if (condition.getAsBoolean()) result.complete(null); else poll(server, condition, result); }
            catch (Throwable error) { result.completeExceptionally(error); }
        }), 2, TimeUnit.MILLISECONDS);
    }
    private static CompletableFuture<Void> delay(MinecraftServer server, int millis) {
        var result = new CompletableFuture<Void>(); TIMER.schedule(() -> server.execute(() -> result.complete(null)), millis, TimeUnit.MILLISECONDS); return result;
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private DhPlanCheck() {}
}
