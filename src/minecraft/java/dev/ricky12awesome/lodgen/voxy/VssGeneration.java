package dev.ricky12awesome.lodgen.voxy;

import dev.ricky12awesome.lodgen.GenerationWorkers;
import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.minecraft.CommandText;
import dev.ricky12awesome.lodgen.minecraft.RendererSinks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.storage.LevelResource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/** Dedicated-server output uses VSS's serializer and transport, with no client Voxy classes. */
public final class VssGeneration {
    private static final Map<Object, Session> SESSIONS = new IdentityHashMap<>();
    private static final Map<MinecraftServer, Set<UUID>> WARNED = new IdentityHashMap<>();
    private static Throwable failure;
    private static final ThreadLocal<ServerLevel> MASK_LEVEL = new ThreadLocal<>();

    public static ServerLevel maskLevel(ServerLevel source) {
        ServerLevel target = MASK_LEVEL.get();
        return target == null ? source : target;
    }

    public static void tick(MinecraftServer server) {
        if (!server.isDedicatedServer() || server.isStopped()) return;
        try {
            warn(server);
            if (failure != null) return;
            Object service = call(type("networking.server.LSSServerNetworking"), "getRequestService");
            if (service == null) return;
            synchronized (SESSIONS) {
                if (!SESSIONS.containsKey(service)) {
                    var session = new Session(server, service);
                    SESSIONS.put(service, session);
                    try { session.attach(); }
                    catch (Throwable error) { SESSIONS.remove(service); session.close(); throw error; }
                    RendererSinks.voxy(session);
                }
                SESSIONS.get(service).refreshMask();
            }
        } catch (Throwable error) {
            if (failure == null) LodgenConfig.LOGGER.error("Cannot initialize Voxy Server Side output", error);
            failure = error;
        }
    }

    public static boolean generationConfigured() throws ReflectiveOperationException {
        Object config = type("config.LSSServerConfig").getField("CONFIG").get(null);
        return (boolean) call(config, "generationConfiguredForRestart");
    }

    private static void warn(MinecraftServer server) throws ReflectiveOperationException {
        boolean configured = generationConfigured();
        if (!configured) { WARNED.remove(server); return; }
        Set<UUID> warned = WARNED.get(server);
        if (warned == null) {
            warned = new HashSet<>();
            WARNED.put(server, warned);
            LodgenConfig.LOGGER.warn(CommandText.plain(CommandText.message("lodgen.warning.vss_generation_disabled")));
        }
        Set<UUID> online = new HashSet<>();
        for (var player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            // #if MC_1211
            boolean operator = server.getPlayerList().isOp(player.getGameProfile());
            // #else
            boolean operator = server.getPlayerList().isOp(new net.minecraft.server.players.NameAndId(player.getGameProfile()));
            // #endif
            if (operator && warned.add(player.getUUID()))
                player.sendSystemMessage(CommandText.message("lodgen.warning.vss_generation_disabled"));
        }
        warned.retainAll(online);
    }

    /** Getter hook also covers VSS's save-time invalidations. Keep its native field intact. */
    public static Object store(Object service, Object original) {
        synchronized (SESSIONS) {
            var session = SESSIONS.get(service);
            return session == null ? original : session.proxy;
        }
    }

    public static boolean invalidate(Object service) {
        synchronized (SESSIONS) {
            var session = SESSIONS.get(service);
            if (session != null) try { session.columns.clear(); }
            catch (Exception error) { throw new IllegalStateException("Cannot clear LODgen VSS columns", error); }
            return session != null;
        }
    }

    public static void serverStopping(MinecraftServer server) {
        synchronized (SESSIONS) {
            for (var session : SESSIONS.values()) if (session.server == server) session.stopConversions();
        }
        WARNED.remove(server);
    }

    /** VSS has drained its readers and dirty notifications before this hook runs. */
    public static void serviceStopped(Object service) {
        Session session;
        synchronized (SESSIONS) { session = SESSIONS.remove(service); }
        if (session != null) {
            session.close();
            RendererSinks.voxy(null);
        }
        failure = null;
    }

    private record Snapshot(String dimension, long position, byte[] bytes, long stamp, long revision) {}

    private static final class Session implements RendererSinks.VoxySink, AutoCloseable {
        final MinecraftServer server;
        final Object service, original, codec, diagnostics, proxy;
        final Class<?> storeType = type("common.store.LodStoreService");
        final VssColumns columns;
        final VssRegionFiles regions;
        final long maskNonce = System.nanoTime();
        String masks;
        final GenerationWorkers workers = new GenerationWorkers("LODgen VSS output");
        final ReentrantReadWriteLock conversionLock = new ReentrantReadWriteLock();
        final Method serialize = method(type("networking.server.SectionSerializer"), "serializeColumn", 4);
        volatile boolean stopping;

        Session(MinecraftServer server, Object service) throws Exception {
            this.server = server;
            this.service = service;
            original = call(service, "getLodStore");
            codec = call(type("common.store.StoreCodec"), "zstdOrNull");
            if (codec == null) throw new IllegalStateException("VSS's compression codec is unavailable");
            diagnostics = original == null ? type("common.store.LodStoreDiagnostics").getConstructor().newInstance() : call(original, "diagnostics");
            var directory = server.getWorldPath(LevelResource.ROOT).resolve("lodgen");
            Files.createDirectories(directory);
            DataSource source = (DataSource) call(type("common.store.SqliteDriverRuntime"), "dataSource",
                    "jdbc:sqlite:" + directory.resolve("vss-lods.sqlite").toAbsolutePath());
            masks = masks(server, maskNonce);
            columns = new VssColumns(source, identity(server, service) + masks);
            var directories = new java.util.HashMap<String, java.nio.file.Path>();
            for (var level : server.getAllLevels()) {
                // #if MC_1211
                String dimension = level.dimension().location().toString();
                // #else
                String dimension = level.dimension().identifier().toString();
                // #endif
                directories.put(dimension, net.minecraft.world.level.dimension.DimensionType.getStorageFolder(level.dimension(),
                        server.getWorldPath(LevelResource.ROOT)).resolve("region"));
            }
            regions = new VssRegionFiles(directories::get);
            proxy = Proxy.newProxyInstance(storeType.getClassLoader(), new Class<?>[]{storeType}, this::invoke);
        }

        void attach() throws ReflectiveOperationException {
            call(call(service, "getDiskReader"), "attachStore", proxy);
            call(call(service, "getOffThreadProcessor"), "attachStore", proxy);
        }

        void refreshMask() throws Exception {
            String current = masks(server, maskNonce);
            if (!current.equals(masks)) {
                columns.clear();
                masks = current;
            }
        }

        @Override public boolean ready(ServerLevel level) { return !stopping && level.getServer() == server; }
        @Override public int radius(ServerLevel level) {
            try { return (int) call(call(service, "settingsConfig"), "lodDistanceChunks"); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        @Override public CompletableFuture<Void> convert(ServerLevel level, List<ChunkAccess> chunks) { return convert(level, level, chunks); }
        @Override public CompletableFuture<Void> convert(ServerLevel level, ServerLevel source, List<ChunkAccess> chunks) {
            return CompletableFuture.supplyAsync(() -> snapshot(level, source, chunks), server).thenAcceptAsync(this::save, workers);
        }

        List<Snapshot> snapshot(ServerLevel target, ServerLevel source, List<ChunkAccess> chunks) {
            if (stopping) throw new CancellationException("VSS output is stopping");
            // #if MC_1211
            String dimension = target.dimension().location().toString();
            // #else
            String dimension = target.dimension().identifier().toString();
            // #endif
            var snapshots = new ArrayList<Snapshot>();
            long stamp = System.currentTimeMillis() / 1000;
            try {
                for (var chunk : chunks) {
                    LevelChunk full = chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : (LevelChunk) chunk;
                    int x = dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry.x(chunk.getPos());
                    int z = dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry.z(chunk.getPos());
                    long position = ((long) x << 32) | (z & 0xffffffffL);
                    long revision = columns.acquire(dimension, position);
                    try {
                        Object data;
                        // Isolated cave-mode worlds supply the lighting; target-world
                        // privacy still governs the bytes served to connected players.
                        MASK_LEVEL.set(target);
                        try { data = serialize.invoke(null, source, full, x, z); }
                        finally { MASK_LEVEL.remove(); }
                        byte[] bytes = (byte[]) call(data, "serializedSections");
                        snapshots.add(new Snapshot(dimension, position, bytes == null ? new byte[0] : bytes, stamp, revision));
                    } catch (Throwable error) { columns.release(dimension, position, revision); throw error; }
                }
                return snapshots;
            } catch (ReflectiveOperationException | RuntimeException error) {
                for (var snapshot : snapshots) columns.release(snapshot.dimension, snapshot.position, snapshot.revision);
                throw new java.util.concurrent.CompletionException(error);
            }
        }

        void save(List<Snapshot> snapshots) {
            conversionLock.readLock().lock();
            try {
                if (stopping) throw new CancellationException("VSS output is stopping");
                var batch = new ArrayList<VssColumns.Column>();
                for (var snapshot : snapshots) {
                    byte[] frame = snapshot.bytes.length == 0 ? new byte[0] : (byte[]) call(codec, "compress", snapshot.bytes);
                    batch.add(new VssColumns.Column(snapshot.dimension, snapshot.position, frame, snapshot.bytes.length, snapshot.stamp, snapshot.revision));
                }
                columns.put(batch);
            } catch (Exception error) { throw new java.util.concurrent.CompletionException(error); }
            finally {
                for (var snapshot : snapshots) columns.release(snapshot.dimension, snapshot.position, snapshot.revision);
                conversionLock.readLock().unlock();
            }
        }

        Object invoke(Object instance, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            if (name.equals("get") || name.equals("getFrame")) {
                var column = columns.get((String) args[0], (long) args[1]);
                if (column != null && regions.supersedes(column.dimension(), column.position(), column.stamp())) {
                    columns.delete(column.dimension(), new long[]{column.position()});
                    column = null;
                }
                if (column != null) {
                    if (name.equals("getFrame")) return type("common.store.LodStoreService$FrameHit")
                            .getConstructor(byte[].class, int.class, long.class).newInstance(column.frame(), column.size(), column.stamp());
                    byte[] bytes = column.size() == 0 ? new byte[0] : (byte[]) call(codec, "decompress", column.frame(), column.size());
                    return type("common.store.LodStoreService$StoreHit").getConstructor(byte[].class, long.class).newInstance(bytes, column.stamp());
                }
            }
            if (name.equals("invalidate")) columns.delete((String) args[0], (long[]) args[1]);
            if (name.equals("delete")) columns.delete((String) args[0], new long[]{(long) args[1]});
            if (original != null) try { return method.invoke(original, args); }
            catch (InvocationTargetException error) { throw error.getCause(); }
            return switch (name) {
                case "mode" -> type("common.store.LodStoreMode").getField("FULL").get(null);
                case "diagnostics" -> diagnostics;
                case "stateToken" -> "ok";
                case "migrationStatusToken" -> "";
                case "deposit", "depositFrame" -> false;
                case "equals" -> instance == args[0];
                case "hashCode" -> System.identityHashCode(instance);
                case "toString" -> "LODgen VSS columns";
                default -> null;
            };
        }

        void stopConversions() {
            stopping = true;
            workers.shutdown();
            conversionLock.writeLock().lock();
            conversionLock.writeLock().unlock();
        }
        @Override public void close() {
            stopConversions();
            try { columns.close(); }
            catch (Exception error) { LodgenConfig.LOGGER.error("Cannot close LODgen VSS columns", error); }
        }
    }

    private static String identity(MinecraftServer server, Object service) throws ReflectiveOperationException {
        Object settings = call(call(service, "settingsConfig"), "snapshot");
        // VSS's canonical v20 dictionaries use names; changing registry contents or
        // masking policy still invalidates generated bytes just as in its native cache.
        var states = new StringBuilder("v20\n").append(server.getServerVersion()).append('\n').append(call(settings, "privacy"));
        for (var state : net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY) states.append('\n').append(state);
        // #if MC_1211
        var biomes = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
        // #else
        var biomes = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        // #endif
        for (var biome : biomes) states.append('\n').append(biomes.getKey(biome));
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(states.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }

    private static String masks(MinecraftServer server, long nonce) throws ReflectiveOperationException {
        var result = new java.util.TreeMap<String, String>();
        Class<?> manager = type("networking.server.XrayMaskManager");
        for (var level : server.getAllLevels()) {
            Object entry = call(manager, "entryForActive", level);
            String fingerprint;
            if (!(boolean) call(manager, "isTerminalForActive", level)) fingerprint = "transient:" + nonce;
            else if (entry == null) fingerprint = "off";
            else fingerprint = call(entry, "sourceLabel") + ":" + call(call(entry, "mask"), "fingerprint");
            result.put(level.dimension().toString(), level.getSeed() + ":" + fingerprint);
        }
        return result.toString();
    }

    private static Class<?> type(String suffix) {
        try { return Class.forName("dev.vox.lss." + suffix); }
        catch (ClassNotFoundException error) { throw new IllegalStateException("Unsupported VSS API: " + suffix, error); }
    }
    private static Method method(Class<?> type, String name, int count) {
        for (var method : type.getMethods()) if (method.getName().equals(name) && method.getParameterCount() == count) return method;
        throw new IllegalStateException("Unsupported VSS API: " + type.getName() + "." + name);
    }
    private static Object call(Object target, String name, Object... args) throws ReflectiveOperationException {
        return method(target instanceof Class<?> type ? type : target.getClass(), name, args.length)
                .invoke(target instanceof Class<?> ? null : target, args);
    }
}
