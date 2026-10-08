package dev.ricky12awesome.lodgen.integration;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.generation.GenerationArea;
import dev.ricky12awesome.lodgen.minecraft.GenerationTasks;
import dev.ricky12awesome.lodgen.task.TaskProgress;
import dev.ricky12awesome.lodgen.task.TaskRecord;
import dev.ricky12awesome.lodgen.task.TaskStore;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Dedicated-server regression for LODgen-owned native VSS generation. */
public final class VssCheck {
    private static final Path REPORT = Path.of("integration-result.txt");
    private static final int BLOCK_X = 65_536, BLOCK_Z = -65_536;
    private static final int CHUNK_X = Math.floorDiv(BLOCK_X, 16), CHUNK_Z = Math.floorDiv(BLOCK_Z, 16);
    private static final int[] TARGET_X = {CHUNK_X - 1, CHUNK_X};
    private static final int[] TARGET_Z = {CHUNK_Z - 1, CHUNK_Z};
    private static final long STARTED = System.nanoTime();
    private static final boolean RELOAD = Boolean.getBoolean("lodgen.test.vssReload");
    private static final boolean WITH_DH = Boolean.getBoolean("lodgen.test.vssDh");
    private static final boolean STORE_ENABLED = Boolean.getBoolean("lodgen.test.vssStore");
    private static final boolean CONFIGURED_GENERATION = Boolean.getBoolean("lodgen.test.vssGeneration");
    private static ScheduledExecutorService timer;
    private static MinecraftServer server;
    private static Object service, store, generationService, syntheticPlayer;
    private static UUID syntheticPlayerId;
    private static boolean reconciled, reconcilingSettings, reloadWitnessChecked, nativeOutputsVerified, demandVerified,
            prefillStarted, prefillComplete, requested, done;
    private static long submittedBefore, submittedAtStartup;
    private static boolean cacheWitnessWritten;
    private static long lastNativeRequestAt;
    private static final Path CACHE_WITNESS = Path.of("vss-native-witness.bin");
    private static final ConcurrentLinkedQueue<Object> NATIVE_PACKETS = new ConcurrentLinkedQueue<>();
    private static final java.util.Set<Long> NATIVE_COLUMNS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private VssCheck() {}

    public static void run(MinecraftServer current) {
        server = current;
        try {
            REPORT.toFile().delete();
            require(server.isDedicatedServer(), "VSS regression requires a dedicated server");
            timer = Executors.newSingleThreadScheduledExecutor(task -> daemon(task, "LODgen VSS regression"));
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
            require(System.nanoTime() - STARTED < TimeUnit.SECONDS.toNanos(180), "VSS native generation regression timed out");
            if (!dev.ricky12awesome.lodgen.voxy.VssGeneration.ready(server.overworld())) return;
            if (service == null) { initialize(); if (service == null) return; }
            if (!reconciled) { if (!reconcilingSettings) reconcileRestrictiveSettings(); return; }
            if (RELOAD && STORE_ENABLED && !reloadWitnessChecked) {
                if (!verifyNativeStore(false)) return;
                reloadWitnessChecked = true;
                require((long) invoke(generationService, "getTotalSubmitted") == 0,
                        "VSS regenerated a cached column during native-store startup");
            }
            // Exercise native client demand while the DH sink is attached, before a task can
            // populate VSS's cache and turn this into a cache-only response test.
            if (!RELOAD && WITH_DH && !demandVerified) {
                if (!requested) { submitNativeRequests(); return; }
                if (!nativeResponsesComplete()) {
                    if (System.nanoTime() - lastNativeRequestAt >= TimeUnit.SECONDS.toNanos(2)) {
                        offerMissingNativeRequests();
                    }
                    return;
                }
                if (!verifyDhOutput()) return;
                demandVerified = true;
                requested = false;
                NATIVE_COLUMNS.clear();
            }
            if (STORE_ENABLED && !RELOAD && !prefillComplete) {
                if (!prefillStarted) { startTask(); return; }
                if (!taskCompleteOrFail()) return;
                if (!verifyNativeStore(true) || WITH_DH && !verifyDhOutput()) return;
                prefillComplete = true;
            }
            if (!requested) { submitNativeRequests(); return; }
            if (!nativeResponsesComplete()) {
                if (System.nanoTime() - lastNativeRequestAt >= TimeUnit.SECONDS.toNanos(2)) {
                    offerMissingNativeRequests();
                }
                return;
            }
            if (!nativeOutputsVerified) {
                if (verifyNativeStore(true) && (!WITH_DH || verifyDhOutput())) nativeOutputsVerified = true;
                else return;
            }
            if (WITH_DH && !STORE_ENABLED && !RELOAD && !prefillComplete) {
                if (!prefillStarted) { startTask(); return; }
                if (taskCompleteOrFail()) prefillComplete = true;
                else return;
            }
            Files.writeString(REPORT, "PASS: VSS-native requests returned both requested columns despite restrictive VSS settings;"
                    + (STORE_ENABLED ? RELOAD
                            ? " all four native cache rows survived restart byte-for-byte and served cached packets with zero generation;"
                            : " an explicit task warmed all four native SQLite cache rows;"
                            : " cache-off requests generated native packets;")
                    + (WITH_DH ? " DH stored the same generated target columns;" : "")
                    + " VSS configuration stayed unchanged.\n");
            finish();
        } catch (Throwable failure) { fail(failure); }
    }

    private static void initialize() throws Exception {
        service = invokeStatic("dev.vox.lss.networking.server.LSSServerNetworking", "getRequestService");
        if (service == null) return;
        generationService = invoke(service, "getGenerationService");
        require(generationService != null, "VSS native generation service is unavailable");
        submittedAtStartup = (long) invoke(generationService, "getTotalSubmitted");
        if (RELOAD && STORE_ENABLED) require(submittedAtStartup == 0,
                "VSS generated chunks before the restart cache request");
        require((boolean) invoke(service, "generationEnabledForSession"),
                "VSS did not advertise LODgen-owned generation as enabled");
        Object settings = invoke(service, "settingsConfig");
        assertConfiguredGeneration(invoke(settings, "configuredSnapshot"), CONFIGURED_GENERATION);
        assertEffectiveFacade(settings);
        store = invoke(service, "getLodStore");
        require((store != null) == STORE_ENABLED, "VSS native LOD store did not follow its configured cache switch");
        if (STORE_ENABLED) require(store.getClass().getName().contains("SqliteLodStore"),
                "VSS cache is not the native SQLite store: " + store.getClass().getName());
        if (WITH_DH) require(dhWrapper(server.overworld()) != null, "DH did not expose the dedicated test level");
        require(LodgenConfig.INSTANCE.enabled(), "LODgen generation must remain enabled during the VSS regression");
        installSyntheticClient(server.overworld());
    }

    @SuppressWarnings("unchecked")
    private static void reconcileRestrictiveSettings() throws Exception {
        reconcilingSettings = true;
        Object config = invoke(service, "settingsConfig");
        Object before = invoke(config, "snapshot");
        Map<String, Object> values = new LinkedHashMap<>((Map<String, Object>) invoke(before, "values"));
        values.put("generation.enabled", false);
        values.put("generation.concurrency.global", 1);
        values.put("generation.concurrency.per_player", 1);
        values.put("generation.timeout_ticks", 1);
        Class<?> settingsType = classFor("dev.vox.lss.common.config.ServerSettings");
        Object after = settingsType.getMethod("fromValues", Map.class).invoke(null, values);
        Object receipt = invoke(service, "reconcileSettings",
                new Class<?>[]{settingsType, settingsType, long.class}, before, after, System.currentTimeMillis());
        ((java.util.concurrent.CompletableFuture<?>) receipt).whenComplete((ignored, failure) -> server.execute(() -> {
            try {
                if (failure != null) throw new IllegalStateException("VSS restrictive settings reconciliation failed", failure);
                Object currentSettings = invoke(service, "settingsConfig");
                assertConfiguredGeneration(invoke(currentSettings, "configuredSnapshot"), CONFIGURED_GENERATION);
                require(((Map<?, ?>) invoke(invoke(currentSettings, "snapshot"), "values"))
                                .equals((Map<?, ?>) invoke(before, "values")),
                        "VSS settings reconciliation changed the configured snapshot");
                Object requestedGeneration = invoke(after, "generation");
                require(!(boolean) invoke(requestedGeneration, "enabled"),
                        "Restrictive VSS reconciliation record did not request disabled generation");
                Object requestedConcurrency = invoke(requestedGeneration, "concurrency");
                require((int) invoke(requestedConcurrency, "global") == 1
                                && (int) invoke(requestedConcurrency, "perPlayer") == 1,
                        "Restrictive VSS reconciliation record did not request concurrency one");
                assertEffectiveFacade(currentSettings);
                require((boolean) invoke(service, "generationEnabledForSession"),
                        "VSS disabled its native generation advertisement after settings reconciliation");
                int global = (int) field(generationService, "maxConcurrent");
                int perPlayer = (int) field(generationService, "maxPerPlayerActive");
                int timeout = (int) field(generationService, "timeoutTicks");
                require(global > 1 && perPlayer > 1 && timeout > 1
                                && (boolean) field(generationService, "admissionEnabled"),
                        "VSS native generation caps still obey restrictive settings: " + global + "/" + perPlayer);
                reconciled = true;
            } catch (Throwable error) { fail(error); }
            finally { reconcilingSettings = false; }
        }));
    }

    private static void assertConfiguredGeneration(Object settings, boolean enabled) throws Exception {
        Object generation = invoke(settings, "generation");
        require((boolean) invoke(generation, "enabled") == enabled,
                "VSS changed its configured generation.enabled value");
        Object concurrency = invoke(generation, "concurrency");
        require((int) invoke(concurrency, "global") == 1 && (int) invoke(concurrency, "perPlayer") == 1,
                "VSS fixture did not retain the configured concurrency limit of one");
        require((int) invoke(generation, "timeoutTicks") == 1,
                "VSS fixture did not retain the configured generation timeout of one tick");
    }

    private static void assertEffectiveFacade(Object config) throws Exception {
        require((boolean) invoke(config, "enableChunkGeneration"),
                "VSS effective generation facade did not keep native generation enabled");
        Object limits = invoke(config, "generationLimits");
        require((int) invoke(limits, "global") > 1 && (int) invoke(limits, "perPlayer") > 1,
                "VSS effective concurrency facade still obeys restrictive settings");
        require((int) invoke(config, "generationTimeoutTicks") > 1,
                "VSS effective timeout facade still obeys the restrictive setting");
    }

    private static void installSyntheticClient(ServerLevel level) throws Exception {
        syntheticPlayerId = UUID.randomUUID();
        Class<?> profileType = classFor("com.mojang.authlib.GameProfile");
        Object profile = profileType.getConstructor(UUID.class, String.class).newInstance(syntheticPlayerId, "LodgenVssProbe");
        Class<?> factory = classFor("net.neoforged.neoforge.common.util.FakePlayerFactory");
        syntheticPlayer = factory.getMethod("get", ServerLevel.class, profileType).invoke(null, level, profile);
        addSyntheticPlayerToServerLookup();
        invoke(service, "registerPlayer", new Class<?>[]{classFor("net.minecraft.server.level.ServerPlayer"), int.class},
                syntheticPlayer, classFor("dev.vox.lss.common.LSSConstants").getField("CAPABILITY_VOXEL_COLUMNS").getInt(null));
        invoke(syntheticPlayer, "setPos", new Class<?>[]{double.class, double.class, double.class},
                (TARGET_X[0] + 64) * 16.0 + 8.0, 80.0, CHUNK_Z * 16.0 + 8.0);
    }

    @SuppressWarnings("unchecked")
    private static void addSyntheticPlayerToServerLookup() throws Exception {
        Object playerList = invoke(server, "getPlayerList");
        Class<?> playerListType = classFor("net.minecraft.server.players.PlayerList");
        var uuidsField = playerListType.getDeclaredField("playersByUUID");
        uuidsField.setAccessible(true);
        ((Map<UUID, Object>) uuidsField.get(playerList)).put(syntheticPlayerId, syntheticPlayer);
        require(invoke(playerList, "getPlayer", new Class<?>[]{UUID.class}, syntheticPlayerId) == syntheticPlayer,
                "VSS probe player was not visible through the server player list");
    }

    @SuppressWarnings("unchecked")
    private static void removeSyntheticPlayerFromServerLookup() throws Exception {
        if (syntheticPlayer == null) return;
        Object playerList = invoke(server, "getPlayerList");
        Class<?> playerListType = classFor("net.minecraft.server.players.PlayerList");
        var uuidsField = playerListType.getDeclaredField("playersByUUID");
        uuidsField.setAccessible(true);
        ((Map<UUID, Object>) uuidsField.get(playerList)).remove(syntheticPlayerId, syntheticPlayer);
    }

    private static void startTask() throws Exception {
        GenerationTasks.get(server).start(server.overworld(), area());
        TaskRecord record = currentRecord();
        require(record != null && record.dh() == WITH_DH && record.voxy(),
                "Dedicated task selected the wrong renderer sinks");
        require(record.area().radius() == 1 && record.area().savedRadius() == 0,
                "VSS task did not use the small fixture area");
        prefillStarted = true;
    }

    private static GenerationArea area() { return new GenerationArea(BLOCK_X, BLOCK_Z, 1, 0); }
    private static TaskRecord currentRecord() throws Exception {
        return TaskStore.read(server.getWorldPath(LevelResource.ROOT).resolve("lodgen/task.toml"));
    }

    private static boolean taskCompleteOrFail() throws Exception {
        TaskRecord record = currentRecord();
        if (record == null) return false;
        TaskProgress.Snapshot progress = record.progress();
        if (progress.state() == TaskProgress.State.PAUSED || progress.state() == TaskProgress.State.STOPPED) {
            throw new AssertionError("LODgen generation task ended in " + progress.state()
                    + " after " + progress.prefix() + " batches: "
                    + (progress.error().isBlank() ? "no failure details were recorded" : progress.error()));
        }
        return progress.state() == TaskProgress.State.COMPLETE;
    }

    private static void submitNativeRequests() throws Exception {
        NATIVE_PACKETS.clear();
        NATIVE_COLUMNS.clear();
        long[] positions = {packed(TARGET_X[0], CHUNK_Z), packed(TARGET_X[1], CHUNK_Z)};
        Class<?> payloadType = classFor("dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload");
        Object payload = payloadType.getConstructor(long[].class, long[].class, int.class)
                .newInstance(positions, new long[positions.length], positions.length);
        submittedBefore = (long) invoke(generationService, "getTotalSubmitted");
        invoke(service, "handleBatchRequest", new Class<?>[]{classFor("net.minecraft.server.level.ServerPlayer"), payloadType},
                syntheticPlayer, payload);
        lastNativeRequestAt = System.nanoTime();
        requested = true;
    }

    private static void offerMissingNativeRequests() throws Exception {
        var missing = new java.util.ArrayList<Long>();
        for (int x : TARGET_X) {
            long position = packed(x, CHUNK_Z);
            if (!NATIVE_COLUMNS.contains(position)) missing.add(position);
        }
        if (missing.isEmpty()) return;
        long[] positions = new long[missing.size()];
        for (int index = 0; index < missing.size(); index++) positions[index] = missing.get(index);
        Class<?> payloadType = classFor("dev.vox.lss.networking.payloads.BatchChunkRequestC2SPayload");
        Object payload = payloadType.getConstructor(long[].class, long[].class, int.class)
                .newInstance(positions, new long[positions.length], positions.length);
        invoke(service, "handleBatchRequest", new Class<?>[]{classFor("net.minecraft.server.level.ServerPlayer"), payloadType},
                syntheticPlayer, payload);
        lastNativeRequestAt = System.nanoTime();
    }

    private static boolean nativeResponsesComplete() throws Exception {
        var returned = new java.util.HashSet<Long>();
        Object packet;
        while ((packet = NATIVE_PACKETS.poll()) != null) {
            if (!packet.getClass().getName().endsWith("VoxelColumnS2CPayload")) continue;
            int x = (int) invoke(packet, "chunkX"), z = (int) invoke(packet, "chunkZ");
            long position = packed(x, z);
            require(position == packed(TARGET_X[0], CHUNK_Z) || position == packed(TARGET_X[1], CHUNK_Z),
                    "VSS sent an unexpected native column to the probe: " + x + "," + z);
            byte source = (byte) invoke(packet, "source");
            if ((RELOAD || prefillComplete) && STORE_ENABLED) require(source == (byte) 3,
                    "Restart VSS column did not come from its native store: source=" + source);
            else if (!STORE_ENABLED || WITH_DH && !RELOAD && !demandVerified) require(source == (byte) 2,
                    "Cache-off VSS column did not come from native generation: source=" + source);
            byte[] bytes = (byte[]) invoke(packet, "shippedSections");
            require(bytes.length > 0, "VSS returned an empty native column response at " + x + "," + z);
            if (source == (byte) 3) {
                NativeWitness witness = witnessFor(position, readNativeWitness());
                require(witness != null && java.util.Arrays.equals(bytes, witness.raw),
                        "VSS packet did not carry the witnessed native-store column bytes");
            }
            returned.add(position);
            NATIVE_COLUMNS.add(position);
        }
        if (NATIVE_COLUMNS.size() == 2) {
            require(NATIVE_COLUMNS.contains(packed(TARGET_X[0], CHUNK_Z)) && NATIVE_COLUMNS.contains(packed(TARGET_X[1], CHUNK_Z)),
                    "VSS native responses did not contain both requested targets");
            long submitted = (long) invoke(generationService, "getTotalSubmitted");
            if ((RELOAD || prefillComplete) && STORE_ENABLED) require(submitted == submittedBefore,
                    "VSS regenerated an explicitly prefilled native-store column");
            else require(submitted - submittedBefore >= 2,
                    "VSS requests did not pass through native generation");
            return true;
        }
        long generated = (long) invoke(generationService, "getTotalSubmitted") - submittedBefore;
        require(generated < 2 || System.nanoTime() - STARTED < TimeUnit.SECONDS.toNanos(160),
                "VSS did not return all requested native columns");
        return false;
    }

    private static boolean verifyNativeStore(boolean writeWitness) throws Exception {
        if (!STORE_ENABLED) return true;
        var expected = RELOAD ? readNativeWitness() : java.util.List.<NativeWitness>of();
        if (RELOAD) require(expected.size() == TARGET_X.length * TARGET_Z.length,
                "VSS restart witness does not contain every prefilled column");
        var observed = new java.util.ArrayList<NativeWitness>();
        for (int x : TARGET_X) for (int z : TARGET_Z) {
            long position = packed(x, z);
            Object row = invoke(store, "get", new Class<?>[]{String.class, long.class}, "minecraft:overworld", position);
            if (row == null) return false;
            byte[] section = (byte[]) invoke(row, "sectionBytes");
            require(section.length > 0, "VSS native SQLite row is empty at " + x + "," + z);
            long timestamp = (long) invoke(row, "columnTimestamp");
            Object frameHit = invoke(store, "getFrame", new Class<?>[]{String.class, long.class}, "minecraft:overworld", position);
            if (frameHit == null) return false;
            byte[] frame = (byte[]) invoke(frameHit, "frame");
            int rawSize = (int) invoke(frameHit, "usize");
            require(rawSize == section.length, "VSS native frame raw size differs from its raw column");
            require(timestamp == (long) invoke(frameHit, "columnTimestamp"),
                    "VSS native raw and frame timestamps differ");
            var witness = new NativeWitness(position, section, frame, timestamp, rawSize);
            if (RELOAD) {
                var prior = expected.get(observed.size());
                require(prior.position == witness.position && prior.timestamp == witness.timestamp
                                && prior.rawSize == witness.rawSize && java.util.Arrays.equals(prior.raw, witness.raw)
                                && java.util.Arrays.equals(prior.frame, witness.frame),
                        "VSS native store changed across restart for " + x + "," + z);
            }
            observed.add(witness);
        }
        if (!RELOAD && writeWitness && !cacheWitnessWritten) {
            writeNativeWitness(observed);
            cacheWitnessWritten = true;
        } else if (RELOAD || cacheWitnessWritten) compareWitness(observed, readNativeWitness());
        return true;
    }

    private static void compareWitness(java.util.List<NativeWitness> observed, java.util.List<NativeWitness> expected) {
        require(expected.size() == observed.size(), "VSS native witness column count changed");
        for (int index = 0; index < observed.size(); index++) {
            var prior = expected.get(index);
            var current = observed.get(index);
            require(prior.position == current.position && prior.timestamp == current.timestamp
                            && prior.rawSize == current.rawSize && java.util.Arrays.equals(prior.raw, current.raw)
                            && java.util.Arrays.equals(prior.frame, current.frame),
                    "VSS native store changed across restart for " + current.position);
        }
    }

    private static NativeWitness witnessFor(long position, java.util.List<NativeWitness> columns) {
        for (var column : columns) if (column.position == position) return column;
        return null;
    }

    private record NativeWitness(long position, byte[] raw, byte[] frame, long timestamp, int rawSize) {}

    private static void writeNativeWitness(java.util.List<NativeWitness> columns) throws Exception {
        try (var output = new java.io.DataOutputStream(Files.newOutputStream(CACHE_WITNESS))) {
            output.writeInt(1);
            output.writeInt(columns.size());
            for (var column : columns) {
                output.writeLong(column.position);
                output.writeLong(column.timestamp);
                output.writeInt(column.rawSize);
                output.writeInt(column.raw.length);
                output.write(column.raw);
                output.writeInt(column.frame.length);
                output.write(column.frame);
            }
        }
    }

    private static java.util.List<NativeWitness> readNativeWitness() throws Exception {
        try (var input = new java.io.DataInputStream(Files.newInputStream(CACHE_WITNESS))) {
            require(input.readInt() == 1, "Unsupported VSS native cache witness version");
            int count = input.readInt();
            require(count >= 0 && count <= 64, "Invalid VSS native cache witness size");
            var columns = new java.util.ArrayList<NativeWitness>(count);
            for (int index = 0; index < count; index++) {
                long position = input.readLong(), timestamp = input.readLong();
                int rawSize = input.readInt();
                byte[] raw = input.readNBytes(input.readInt());
                byte[] frame = input.readNBytes(input.readInt());
                require(raw.length > 0 && frame.length > 0, "VSS native cache witness has an empty body");
                columns.add(new NativeWitness(position, raw, frame, timestamp, rawSize));
            }
            require(input.read() == -1, "VSS native cache witness has trailing data");
            return columns;
        }
    }

    private static boolean verifyDhOutput() throws Exception {
        Object wrapper = dhWrapper(server.overworld());
        require(wrapper != null, "DH level wrapper disappeared during VSS generation");
        Object dhLevel = invoke(wrapper, "getDhLevel");
        Object provider = invoke(dhLevel, "getFullDataProvider");
        Class<?> sectionPos = classFor("com.seibel.distanthorizons.core.pos.DhSectionPos");
        for (int x : TARGET_X) {
            int sectionX = Math.floorDiv(x, 4), sectionZ = Math.floorDiv(CHUNK_Z, 4);
            long section = (long) sectionPos.getMethod("encode", byte.class, int.class, int.class)
                    .invoke(null, (byte) 6, sectionX, sectionZ);
            Object data = invoke(provider, "get", new Class<?>[]{long.class}, section);
            if (data == null) return false;
            try {
                int blockX = Math.floorMod(x, 4) * 16 + 8;
                int blockZ = Math.floorMod(CHUNK_Z, 4) * 16 + 8;
                Object column = invoke(data, "getApiDataPointColumn", new Class<?>[]{int.class, int.class},
                        blockX, blockZ);
                if (column == null || ((java.util.Collection<?>) column).isEmpty()) return false;
            } finally {
                invoke(data, "close");
            }
        }
        return true;
    }

    private static Object dhWrapper(ServerLevel level) throws Exception {
        Object delayed = classFor("com.seibel.distanthorizons.api.DhApi$Delayed").getField("worldProxy").get(null);
        Object worldProxy = delayed;
        if (worldProxy == null || !(boolean) invoke(worldProxy, "worldLoaded")) return null;
        for (Object candidate : (Iterable<?>) invoke(worldProxy, "getAllLoadedLevelWrappers"))
            if (invoke(candidate, "getWrappedMcObject") == level) return candidate;
        return null;
    }

    /** Called by the integration mixin; swallow all outbound packets for the probe. */
    public static boolean captureNativePacket(Object player, Object payload) {
        try {
            if (syntheticPlayerId == null || !syntheticPlayerId.equals(invoke(player, "getUUID"))) return false;
            NATIVE_PACKETS.add(payload);
            return true;
        } catch (Throwable error) {
            LodgenConfig.LOGGER.error("Cannot capture VSS native packet", error);
            return false;
        }
    }

    private static long packed(int x, int z) { return ((long) x << 32) | (z & 0xffff_ffffL); }

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
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    private static void finish() {
        done = true;
        if (service != null && syntheticPlayerId != null) try {
            invoke(service, "removePlayer", new Class<?>[]{UUID.class}, syntheticPlayerId);
        } catch (Throwable ignored) {}
        try { removeSyntheticPlayerFromServerLookup(); } catch (Throwable ignored) {}
        if (timer != null) timer.shutdownNow();
        server.halt(false);
    }
    private static void fail(Throwable failure) {
        if (done) return;
        LodgenConfig.LOGGER.error("VSS native generation regression failed", failure);
        try { Files.writeString(REPORT, "FAIL: " + failure + "\n"); } catch (Exception ignored) {}
        finish();
    }
}
