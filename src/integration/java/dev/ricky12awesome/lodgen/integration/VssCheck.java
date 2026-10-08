package dev.ricky12awesome.lodgen.integration;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.generation.GenerationArea;
import dev.ricky12awesome.lodgen.minecraft.GenerationTasks;
import dev.ricky12awesome.lodgen.task.TaskProgress;
import dev.ricky12awesome.lodgen.task.TaskRecord;
import dev.ricky12awesome.lodgen.task.TaskStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Dedicated server regression for VSS-only generation output and its kill switch. */
public final class VssCheck {
    private static final Path REPORT = Path.of("integration-result.txt");
    private static final int BLOCK_X = 65_552, BLOCK_Z = -65_520;
    private static final int CHUNK_X = Math.floorDiv(BLOCK_X, 16), CHUNK_Z = Math.floorDiv(BLOCK_Z, 16);
    private static final long STARTED = System.nanoTime();
    private static final boolean CONFIGURED = Boolean.getBoolean("lodgen.test.vssGeneration");
    private static final boolean RELOAD = Boolean.getBoolean("lodgen.test.vssReload");
    private static final String[] DIMENSIONS = {"minecraft:overworld", "minecraft:the_nether"};
    private static ScheduledExecutorService timer;
    private static ScheduledExecutorService storeReader;
    private static MinecraftServer server;
    private static Object service;
    private static Object store;
    private static int stage;
    private static boolean checkingStore;
    private static boolean reconcilingSettings;
    private static boolean settingsReconciled;
    private static boolean done;

    private VssCheck() {}

    public static void run(MinecraftServer current) {
        server = current;
        try {
            REPORT.toFile().delete();
            require(server.isDedicatedServer(), "VSS regression requires a dedicated server");
            require(dev.ricky12awesome.lodgen.voxy.VssGeneration.generationConfigured() == CONFIGURED,
                    "VSS original generation.enabled value was not preserved");
            timer = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "LODgen VSS regression"));
            storeReader = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "LODgen VSS store check"));
            timer.scheduleAtFixedRate(() -> server.execute(VssCheck::tick), 0, 50, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) { fail(failure); }
    }

    private static Thread daemon(Runnable task, String name) {
        var thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    private static void tick() {
        if (done) return;
        try {
            require(System.nanoTime() - STARTED < TimeUnit.SECONDS.toNanos(150), "VSS generation regression timed out");
            if (service == null) {
                service = invokeStatic("dev.vox.lss.networking.server.LSSServerNetworking", "getRequestService");
                if (service == null) return;
                boolean sessionEnabled = (boolean) invoke(service, "generationEnabledForSession");
                require(!sessionEnabled, "VSS still advertises generation as enabled to this session");
                var configured = classFor("dev.vox.lss.config.LSSServerConfig").getField("CONFIG").get(null);
                require(!(boolean) invoke(configured, "enableChunkGeneration"), "VSS live generation config was not disabled");
                store = invoke(service, "getLodStore");
                require(store != null, "VSS LOD store is unavailable in the test fixture");
                require(LodgenConfig.INSTANCE.enabled(), "LODgen default generation setting should remain enabled");
                if (RELOAD) {
                    stage = 3;
                }
            }

            if (!settingsReconciled) {
                if (!reconcilingSettings) reconcileGenerationEnabled();
                return;
            }
            if (service != null && !RELOAD && stage == 0) {
                GenerationTasks.get(server).start(server.overworld(), area());
                verifyTask("minecraft:overworld");
                stage = 1;
            }

            if (RELOAD) {
                if (!checkingStore) checkReloadColumn(DIMENSIONS[stage - 3]);
                return;
            }
            if (checkingStore) return;
            TaskRecord record = currentRecord();
            if (record == null || record.progress().state() != TaskProgress.State.COMPLETE) return;
            String dimension = stage == 1 ? DIMENSIONS[0] : DIMENSIONS[1];
            verifyTask(dimension);
            ServerLevel level = stage == 1 ? server.overworld() : server.getLevel(Level.NETHER);
            require(level != null, "VSS test dimension is unavailable: " + dimension);
            checkOutput(dimension, level, () -> {
                try {
                    if (stage == 1) {
                        ServerLevel nether = server.getLevel(Level.NETHER);
                        require(nether != null, "Nether server level is unavailable");
                        GenerationTasks.get(server).start(nether, area());
                        verifyTask("minecraft:the_nether");
                        stage = 2;
                    } else {
                        int active = (int) invoke(invoke(service, "getGenerationService"), "getActiveCount");
                        require(active == 0, "VSS admitted generation work despite its server switch being disabled");
                        Files.writeString(REPORT, "PASS: VSS generation was disabled in the live session without changing its configured value; LODgen stored transient Overworld and Nether LOD output.\n");
                        finish();
                    }
                } catch (Throwable error) { fail(error); }
            });
        } catch (Throwable failure) { fail(failure); }
    }

    @SuppressWarnings("unchecked")
    private static void reconcileGenerationEnabled() throws Exception {
        reconcilingSettings = true;
        Object settingsConfig = invoke(service, "settingsConfig");
        Object previous = invoke(settingsConfig, "snapshot");
        var values = new LinkedHashMap<>((Map<String, Object>) invoke(previous, "values"));
        values.put("generation.enabled", true);
        Class<?> settingsType = classFor("dev.vox.lss.common.config.ServerSettings");
        Object next = settingsType.getMethod("fromValues", Map.class).invoke(null, values);
        Object receipt = invoke(service, "reconcileSettings",
                new Class<?>[]{settingsType, settingsType, long.class}, previous, next, System.currentTimeMillis());
        ((CompletableFuture<?>) receipt).whenComplete((ignored, failure) -> server.execute(() -> {
            reconcilingSettings = false;
            if (failure != null) { fail(failure); return; }
            try {
                require(!((boolean) invoke(service, "generationEnabledForSession")),
                        "VSS enabled generation after settings reconciliation requested true");
                require(!(boolean) invoke(settingsConfig, "enableChunkGeneration"),
                        "VSS live generation gate changed after settings reconciliation");
                require(dev.ricky12awesome.lodgen.voxy.VssGeneration.generationConfigured() == CONFIGURED,
                        "VSS reconciliation rewrote its original generation.enabled setting");
                settingsReconciled = true;
            } catch (Throwable error) { fail(error); }
        }));
    }

    private static void checkReloadColumn(String dimension) {
        ServerLevel level = dimension.equals(DIMENSIONS[0]) ? server.overworld() : server.getLevel(Level.NETHER);
        if (level == null) { fail(new AssertionError("VSS test dimension is unavailable: " + dimension)); return; }
        checkOutput(dimension, level, () -> {
            try {
                if (dimension.equals(DIMENSIONS[0])) stage = 4;
                else {
                    int active = (int) invoke(invoke(service, "getGenerationService"), "getActiveCount");
                    require(active == 0, "VSS admitted generation work despite its server switch being disabled");
                    Files.writeString(REPORT, "PASS: VSS persisted Overworld and Nether LODgen data across restart while generation remained disabled.\n");
                    finish();
                }
            } catch (Throwable error) { fail(error); }
        });
    }

    private static void checkOutput(String dimension, ServerLevel level, Runnable success) {
        if (checkingStore) return;
        checkingStore = true;
        CompletableFuture.supplyAsync(() -> storeWitness(dimension), storeReader)
                .whenComplete((witness, failure) -> server.execute(() -> {
                    if (failure != null) { checkingStore = false; fail(failure); return; }
                    if (witness == null) { checkingStore = false; return; }
                    try { submitDiskRead(dimension, level, witness, success); }
                    catch (Throwable error) { checkingStore = false; fail(error); }
                }));
    }

    private static void submitDiskRead(String dimension, ServerLevel level, StoreWitness witness, Runnable success) throws Exception {
        Object reader = invoke(service, "getDiskReader");
        UUID id = UUID.randomUUID();
        long order = System.nanoTime();
        Class<?> registrationType = classFor("dev.vox.lss.common.processing.RequestRegistration");
        Object registration = registrationType.getConstructor().newInstance();
        boolean registered = false;
        try {
            invoke(reader, "registerPlayer", new Class<?>[]{UUID.class, registrationType}, id, registration);
            registered = true;
            invoke(reader, "submitReadDirect",
                    new Class<?>[]{UUID.class, registrationType, String.class, ServerLevel.class,
                            int.class, int.class, long.class, long.class},
                    id, registration, dimension, level, CHUNK_X, CHUNK_Z, order, 0L);
            Object queue = invoke(reader, "getPlayerQueue", new Class<?>[]{UUID.class, registrationType}, id, registration);
            require(queue instanceof ConcurrentLinkedQueue<?>, "VSS reader did not create a result queue for its registration");
            storeReader.execute(() -> pollDiskRead(reader, id, registration, registrationType, queue,
                    dimension, witness, order, success));
        } catch (Throwable error) {
            if (registered) try { invoke(reader, "removePlayerResults", new Class<?>[]{UUID.class, registrationType}, id, registration); }
            catch (Throwable cleanup) { error.addSuppressed(cleanup); }
            if (error instanceof Exception exception) throw exception;
            if (error instanceof Error fatal) throw fatal;
            throw new IllegalStateException(error);
        }
    }

    private static void pollDiskRead(Object reader, UUID id, Object registration, Class<?> registrationType, Object queue,
                                     String dimension, StoreWitness witness, long order, Runnable success) {
        Object result = null;
        Throwable failure = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        try {
            while (System.nanoTime() < deadline) {
                result = ((ConcurrentLinkedQueue<?>) queue).poll();
                if (result != null) break;
                Thread.sleep(25);
            }
            if (result == null) throw new AssertionError("VSS disk reader did not return a store result for " + dimension);
        } catch (Throwable error) { failure = error; }
        Object finalResult = result;
        Throwable finalFailure = failure;
        server.execute(() -> {
            try {
                invoke(reader, "removePlayerResults", new Class<?>[]{UUID.class, registrationType}, id, registration);
                if (finalFailure != null) { checkingStore = false; fail(finalFailure); return; }
                verifyDiskRead(finalResult, id, dimension, witness, order);
                checkingStore = false;
                success.run();
            } catch (Throwable error) { checkingStore = false; fail(error); }
        });
    }

    private static void verifyDiskRead(Object result, UUID id, String dimension, StoreWitness witness, long order) throws Exception {
        require(id.equals(invoke(result, "playerUuid")), "VSS disk read result used the wrong player registration");
        require(dimension.equals(invoke(result, "dimension")), "VSS disk read result used the wrong dimension");
        require((int) invoke(result, "chunkX") == CHUNK_X && (int) invoke(result, "chunkZ") == CHUNK_Z,
                "VSS disk read result used the wrong chunk");
        require((long) invoke(result, "submissionOrder") == order, "VSS disk read result lost its submission order");
        require(!(boolean) invoke(result, "notFound") && !(boolean) invoke(result, "saturated"),
                "VSS disk reader did not serve the stored LOD column");
        require((boolean) invoke(result, "fromStore"), "VSS disk reader bypassed the attached composite store");
        require((long) invoke(result, "columnTimestamp") == witness.timestamp,
                "VSS disk reader returned a different store timestamp");
        byte[] resultFrame = (byte[]) invoke(result, "frameBytes");
        byte[] resultRaw = (byte[]) invoke(result, "sectionBytes");
        require((resultFrame == null) != (resultRaw == null), "VSS disk result must contain exactly one raw or frame payload");
        if (resultFrame != null) {
            require((int) invoke(result, "frameRawSize") == witness.raw.length,
                    "VSS disk result frame has the wrong raw size");
            require(Arrays.equals(resultFrame, witness.frame), "VSS disk result frame differs from the LOD store frame");
        } else require(Arrays.equals(resultRaw, witness.raw), "VSS disk result raw bytes differ from the LOD store column");
    }

    private record StoreWitness(byte[] raw, byte[] frame, long timestamp) {}

    private static StoreWitness storeWitness(String dimension) {
        try {
            long packed = ((long) CHUNK_X << 32) | (CHUNK_Z & 0xffff_ffffL);
            Object hit = invoke(store, "get", new Class<?>[]{String.class, long.class}, dimension, packed);
            if (hit == null) return null;
            byte[] raw = (byte[]) invoke(hit, "sectionBytes");
            if (raw.length == 0) return null;
            long storedStamp = (long) invoke(hit, "columnTimestamp");
            Object frameHit = invoke(store, "getFrame", new Class<?>[]{String.class, long.class}, dimension, packed);
            require(frameHit != null, "VSS frame lookup missed a column returned by raw lookup");
            byte[] frame = (byte[]) invoke(frameHit, "frame");
            int size = (int) invoke(frameHit, "usize");
            require(size == raw.length, "VSS raw and frame lookups reported different column sizes");
            require(storedStamp == (long) invoke(frameHit, "columnTimestamp"),
                    "VSS raw and frame lookups reported different column timestamps");
            Object codec = invokeStatic("dev.vox.lss.common.store.StoreCodec", "zstdOrNull");
            require(codec != null, "VSS zstd store codec is unavailable");
            byte[] decoded = (byte[]) invoke(codec, "decompress", new Class<?>[]{byte[].class, int.class}, frame, size);
            require(Arrays.equals(raw, decoded), "VSS frame bytes do not decompress to the raw stored column");
            return new StoreWitness(raw, frame, storedStamp);
        } catch (Throwable failure) { throw new IllegalStateException("Cannot read VSS LOD store", failure); }
    }

    private static GenerationArea area() { return new GenerationArea(BLOCK_X, BLOCK_Z, 1, 0); }

    private static TaskRecord currentRecord() throws Exception {
        return TaskStore.read(server.getWorldPath(LevelResource.ROOT).resolve("lodgen/task.toml"));
    }

    private static void verifyTask(String dimension) throws Exception {
        TaskRecord record = currentRecord();
        require(record != null, "LODgen task checkpoint is missing");
        require(record.dimension().equals(dimension), "LODgen task selected the wrong dimension: " + record.dimension());
        require(record.voxy() && !record.dh(), "VSS-only task did not use the Voxy output sink");
        require(record.area().radius() == 1 && record.area().savedRadius() == 0, "VSS test task has the wrong area");
        require(record.progress().error().isEmpty(), "LODgen task failed: " + record.progress().error());
    }

    private static Class<?> classFor(String name) throws ClassNotFoundException {
        return Class.forName(name, true, VssCheck.class.getClassLoader());
    }

    private static Object invokeStatic(String owner, String method) throws Exception {
        return classFor(owner).getMethod(method).invoke(null);
    }

    private static Object invoke(Object target, String method) throws Exception {
        return target.getClass().getMethod(method).invoke(target);
    }

    private static Object invoke(Object target, String method, Class<?>[] signature, Object... args) throws Exception {
        return target.getClass().getMethod(method, signature).invoke(target, args);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void finish() {
        done = true;
        if (timer != null) timer.shutdownNow();
        if (storeReader != null) storeReader.shutdownNow();
        server.halt(false);
    }

    private static void fail(Throwable failure) {
        if (done) return;
        LodgenConfig.LOGGER.error("VSS generation regression failed", failure);
        try { Files.writeString(REPORT, "FAIL: " + failure + "\n"); } catch (Exception ignored) {}
        finish();
    }
}
