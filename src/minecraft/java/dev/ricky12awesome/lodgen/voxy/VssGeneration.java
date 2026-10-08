package dev.ricky12awesome.lodgen.voxy;

import dev.ricky12awesome.lodgen.GenerationWorkers;
import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.ModSupport;
import dev.ricky12awesome.lodgen.minecraft.ChunkGenerationPipeline;
import dev.ricky12awesome.lodgen.minecraft.GenerationCenters;
import dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry;
import dev.ricky12awesome.lodgen.minecraft.RendererSinks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;

/** A generation-batch handoff. VSS owns every serializer, result, store and wire operation. */
public final class VssGeneration {
    private static final Map<Object, Session> SESSIONS = new IdentityHashMap<>();
    private static final Map<Object, Session> CACHES = new IdentityHashMap<>();
    private static final ThreadLocal<ServerLevel> MASK_LEVEL = new ThreadLocal<>();
    private static MinecraftServer stopping;

    public static ServerLevel maskLevel(ServerLevel source) {
        var target = MASK_LEVEL.get(); return target == null ? source : target;
    }
    public static void tick(MinecraftServer server) {
        if (stopping != server && !server.isStopped()) return;
        serverStopping(server);
    }
    public static boolean ready(ServerLevel level) {
        if (!ModSupport.loaded("lss") || stopping == level.getServer() || level.getServer().isStopped()) return false;
        try {
            Object service = requestService();
            return service != null && field(service, "server") == level.getServer() && serviceReady(service)
                    && (boolean) config(service).getClass().getMethod("enabled").invoke(config(service));
        } catch (ReflectiveOperationException error) { return false; }
    }
    /** Pause requests only during the one-time cutover, so old LODs are never regenerated. */
    public static synchronized boolean serviceReady(Object service) {
        try {
            var server = (MinecraftServer) field(service, "server");
            if (stopping == server || server.isStopped()) return false;
            Object generation = service.getClass().getMethod("getGenerationService").invoke(service);
            var session = SESSIONS.computeIfAbsent(generation, Session::new);
            session.server = server;
            if (!session.migrationChecked) {
                if (!VssLegacyMigration.required(server)) session.migrationChecked = true;
                else if (VssLegacyMigration.masksReady(server)) {
                    session.migrationChecked = true;
                    session.migration = VssLegacyMigration.start(server.overworld(), service, session.workers);
                    session.migration.whenComplete((count, error) -> {
                        if (error != null) LodgenConfig.LOGGER.error("Could not transfer existing LODs into the native VSS cache", error);
                        else LodgenConfig.LOGGER.info("Transferred {} existing LODs into the native VSS cache", count);
                    });
                } else return false;
            }
            return session.migration == null || session.migration.isDone() && !session.migration.isCompletedExceptionally();
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot prepare native VSS generation", error); }
    }
    public static int generationRadius(ServerLevel level) {
        int radius = LodgenConfig.INSTANCE.generationDistance();
        if (radius > 0) return radius;
        try {
            Object service = requestService(); if (service == null) return 0;
            Object config = config(service);
            // #if MC_1211
            String dimension = level.dimension().location().toString();
            // #else
            String dimension = level.dimension().identifier().toString();
            // #endif
            return (int) config.getClass().getMethod("lodDistanceForWorld", String[].class)
                    .invoke(config, (Object) new String[]{dimension});
        } catch (ReflectiveOperationException error) { return 0; }
    }
    public static Object generationLimits() {
        try {
            return Class.forName("dev.vox.lss.common.config.ServerConfigBase$GenerationLimits")
                    .getConstructor(int.class, int.class).newInstance(Integer.MAX_VALUE, Integer.MAX_VALUE);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot override native VSS generation admission", error); }
    }
    private static Object requestService() throws ReflectiveOperationException {
        return Class.forName("dev.vox.lss.networking.server.LSSServerNetworking").getMethod("getRequestService").invoke(null);
    }
    private static Object config(Object service) throws ReflectiveOperationException { return service.getClass().getMethod("settingsConfig").invoke(service); }
    /** Hand explicit pre-generation to VSS's native serializer and cache writer. */
    public static CompletableFuture<Void> convert(ServerLevel level, ServerLevel source, List<ChunkAccess> chunks) {
        try {
            Object service = requestService();
            if (!ready(level) || service == null) throw new CancellationException("VSS service is unavailable");
            Object store = service.getClass().getMethod("getLodStore").invoke(service);
            if (store == null) return CompletableFuture.completedFuture(null);
            Object generation = service.getClass().getMethod("getGenerationService").invoke(service);
            Session session;
            synchronized (VssGeneration.class) {
                session = SESSIONS.computeIfAbsent(generation, Session::new);
                session.server = level.getServer();
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    if (!ready(level)) throw new CancellationException("VSS service is closing");
                    Object serializer = field(generation, "columnSerializer");
                    Object filter = service.getClass().getMethod("getDirtyContentFilter").invoke(service);
                    var seed = filter.getClass().getMethod("seed", String.class, int.class, int.class, byte[].class);
                    var clock = Class.forName("dev.vox.lss.common.LSSConstants").getMethod("epochSeconds");
                    var pack = Class.forName("dev.vox.lss.common.PositionUtil").getMethod("packPosition", int.class, int.class);
                    // #if MC_1211
                    String dimension = level.dimension().location().toString();
                    // #else
                    String dimension = level.dimension().identifier().toString();
                    // #endif
                    var columns = new java.util.ArrayList<VssNativeStore.Column>(chunks.size());
                    for (var chunk : chunks) {
                        int x = PersistenceRegistry.x(chunk.getPos()), z = PersistenceRegistry.z(chunk.getPos());
                        long stamp = (long) clock.invoke(null);
                        LevelChunk full = chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : (LevelChunk) chunk;
                        Object data = serializeSource(serializer, level, source, full, x, z);
                        byte[] bytes = (byte[]) data.getClass().getMethod("serializedSections").invoke(data);
                        seed.invoke(filter, dimension, x, z, bytes);
                        columns.add(new VssNativeStore.Column(dimension, (long) pack.invoke(null, x, z), bytes, stamp));
                    }
                    return columns;
                } catch (Throwable error) { throw new CompletionException(error); }
            }, level.getServer()).thenAcceptAsync(columns -> {
                try {
                    VssNativeStore.commit(store, columns, () -> ready(level));
                } catch (Exception error) { throw new CompletionException(error); }
            }, session.workers);
        } catch (ReflectiveOperationException error) { return CompletableFuture.failedFuture(error); }
    }
    public static void add3(Object cache, Object ticket, Object position, int radius, Object service) {
        add(cache, (ChunkPos) position, service);
    }
    public static void add4(Object cache, Object ticket, Object position, int radius, Object argument, Object service) {
        add(cache, (ChunkPos) position, service);
    }
    private static synchronized void add(Object cache, ChunkPos position, Object service) {
        Session session = SESSIONS.computeIfAbsent(service, Session::new);
        ServerLevel level = level(cache);
        session.server = level.getServer();
        CACHES.put(cache, session);
        var key = new Key(cache, PersistenceRegistry.pack(position));
        var existing = session.requests.get(key);
        if (existing != null && !existing.failed) return;
        if (existing != null) existing.retire();
        var request = new Handoff(); session.requests.put(key, request);
        var pipeline = session.pipelines.computeIfAbsent(level, ChunkGenerationPipeline::new);
        pipeline.generate(PersistenceRegistry.x(position), PersistenceRegistry.z(position), 1, 1,
                GenerationCenters.automatic(level, generationRadius(level)), session.workers, batch -> {
                    synchronized (VssGeneration.class) {
                        if (request.retired) return CompletableFuture.completedFuture(null);
                        ChunkAccess generated = batch.chunks.get(0);
                        request.chunk = generated instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : (LevelChunk) generated;
                        request.source = batch.sourceLevel();
                    }
                    var dh = RendererSinks.dhAvailable() && RendererSinks.ready(level, true, false)
                            ? RendererSinks.convert(level, batch.sourceLevel(), batch.chunks, session.workers, true, false)
                            : CompletableFuture.<Void>completedFuture(null);
                    return CompletableFuture.allOf(request.consumed, dh);
                }).whenComplete((ignored, error) -> {
                    synchronized (VssGeneration.class) {
                        if (error != null && !request.retired) request.failed = true;
                    }
                });
    }
    public static void remove3(Object cache, Object ticket, Object position, int radius) { remove(cache, (ChunkPos) position); }
    public static void remove4(Object cache, Object ticket, Object position, int radius, Object argument) { remove(cache, (ChunkPos) position); }
    private static synchronized void remove(Object cache, ChunkPos position) {
        Session session = CACHES.get(cache); if (session == null) return;
        Handoff request = session.requests.remove(new Key(cache, PersistenceRegistry.pack(position)));
        if (request != null) request.retire();
    }
    public static synchronized Object chunk(Object cache, int x, int z) {
        Session session = CACHES.get(cache); if (session == null) return null;
        var request = session.requests.get(new Key(cache, PersistenceRegistry.pack(x, z)));
        return request == null ? null : request.chunk;
    }
    /** Keep the native failure disposition and accounting, including deferred cleanup. */
    public static synchronized void beforeTick(Object service) {
        Session session = SESSIONS.get(service); if (session == null) return;
        try {
            Map<?, ?> active = (Map<?, ?>) field(service, "active");
            for (Object pending : active.values()) {
                var level = (ServerLevel) field(pending, "level");
                var pos = (ChunkPos) field(pending, "pos");
                var request = session.requests.get(new Key(level.getChunkSource(), PersistenceRegistry.pack(pos)));
                if (request != null && request.failed) declaredField(pending, "timeoutTicks").setInt(pending, 0);
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot reconcile native VSS generation failure", error); }
    }
    /** Forward to VSS's original serializer, using source lighting and logical-world privacy. */
    public static Object serialize(Object serializer, Object logical, Object chunk, int x, int z) throws Throwable {
        var level = (ServerLevel) logical;
        ServerLevel source = level;
        synchronized (VssGeneration.class) {
            var session = CACHES.get(level.getChunkSource());
            var request = session == null ? null : session.requests.get(new Key(level.getChunkSource(), PersistenceRegistry.pack(x, z)));
            if (request != null && request.source != null) source = request.source;
        }
        return serializeSource(serializer, level, source, chunk, x, z);
    }
    private static Object serializeSource(Object serializer, ServerLevel level, ServerLevel source, Object chunk, int x, int z) throws Throwable {
        var previous = MASK_LEVEL.get(); MASK_LEVEL.set(level);
        try {
            Class<?> contract = Class.forName("dev.vox.lss.networking.server.ChunkGenerationService$ColumnSerializer");
            var method = contract.getMethod("serialize", ServerLevel.class, LevelChunk.class, int.class, int.class);
            return method.invoke(serializer, source, chunk, x, z);
        } catch (InvocationTargetException error) { throw error.getCause(); }
        finally { if (previous == null) MASK_LEVEL.remove(); else MASK_LEVEL.set(previous); }
    }
    public static synchronized void serverStopping(MinecraftServer server) {
        stopping = server;
        for (var session : SESSIONS.values()) if (session.server == server) session.stop();
    }
    public static synchronized void serviceStopped(Object service) {
        Session session = SESSIONS.remove(service);
        if (session == null) {
            try { session = SESSIONS.remove(field(service, "generationService")); }
            catch (ReflectiveOperationException ignored) { return; }
        }
        if (session == null) return;
        session.stop();
        Session closed = session;
        CACHES.values().removeIf(value -> value == closed); session.workers.shutdown();
    }
    private static ServerLevel level(Object cache) {
        for (Class<?> type = cache.getClass(); type != null; type = type.getSuperclass()) for (var field : type.getDeclaredFields()) {
            if (!ServerLevel.class.isAssignableFrom(field.getType())) continue;
            try { field.setAccessible(true); return (ServerLevel) field.get(cache); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        throw new IllegalStateException("VSS generation cache has no server world");
    }
    private static Field declaredField(Object object, String name) throws NoSuchFieldException {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field;
    }
    private static Object field(Object object, String name) throws ReflectiveOperationException { return declaredField(object, name).get(object); }
    private record Key(Object cache, long position) {}
    private static final class Handoff {
        final CompletableFuture<Void> consumed = new CompletableFuture<>();
        LevelChunk chunk; ServerLevel source; boolean failed, retired;
        void retire() { retired = true; chunk = null; consumed.complete(null); }
    }
    private static final class Session {
        final GenerationWorkers workers = new GenerationWorkers("LODgen VSS generation");
        final Map<ServerLevel, ChunkGenerationPipeline> pipelines = new IdentityHashMap<>();
        final Map<Key, Handoff> requests = new HashMap<>();
        MinecraftServer server;
        boolean migrationChecked;
        CompletableFuture<Integer> migration;
        Session(Object service) {}
        void stop() {
            requests.values().forEach(Handoff::retire); requests.clear();
            pipelines.values().forEach(ChunkGenerationPipeline::close);
        }
    }
}
