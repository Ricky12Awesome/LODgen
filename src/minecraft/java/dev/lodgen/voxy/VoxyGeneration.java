package dev.lodgen.voxy;

import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.GenerationFrontier;
import dev.lodgen.generation.GenerationFrontier.Tile;
import dev.lodgen.generation.TileCoverage;
import dev.lodgen.minecraft.ChunkGenerationPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/** Voxy has no generation queue. This scheduler drives the shared native path
 * around the local player, maintaining a completed frontier for each engine.
 */
public final class VoxyGeneration {
    private static final Map<Object, Session> SESSIONS = new IdentityHashMap<>();
    private static volatile Session current;
    private static volatile net.minecraft.server.MinecraftServer stoppingServer;
    private static VoxyBridge bridge;
    private static boolean failed;

    public static void tick(Minecraft client) {
        if (failed) return;
        if (client.level == null || client.player == null || client.getSingleplayerServer() == null) {
            pause(); return;
        }
        if (client.getSingleplayerServer() == stoppingServer || client.getSingleplayerServer().isStopped()) {
            pause(); return;
        }
        try {
            if (bridge == null) {
                bridge = new VoxyBridge();
                dev.lodgen.minecraft.RendererSinks.voxy(new dev.lodgen.minecraft.RendererSinks.VoxySink() {
                    @Override public boolean ready(ServerLevel level) {
                        try { return session(level) != null; }
                        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
                    }
                    @Override public int radius(ServerLevel level) {
                        try { var target = session(level); return target == null ? 0 : target.context.radius(); }
                        catch (ReflectiveOperationException error) { return 0; }
                    }
                    @Override public CompletableFuture<Void> convert(ServerLevel level, java.util.List<net.minecraft.world.level.chunk.ChunkAccess> chunks) {
                        try {
                            var target = session(level);
                            return target == null ? CompletableFuture.failedFuture(new CancellationException("Voxy is not ready")) : target.convert(chunks);
                        } catch (ReflectiveOperationException error) { return CompletableFuture.failedFuture(error); }
                    }
                });
            }
            var context = bridge.context(client.level);
            if (context == null) { pause(); return; }
            var level = client.getSingleplayerServer().getLevel(client.level.dimension());
            if (level == null) return;
            if (!LodgenConfig.INSTANCE.enabled() || dev.lodgen.minecraft.GenerationTasks.overridesAutomatic(level)) { pause(); return; }
            current = session(level);
            if (current == null) return;
            int radius = LodgenConfig.INSTANCE.generationDistance() > 0 ? LodgenConfig.INSTANCE.generationDistance() : context.radius();
            var center = dev.lodgen.minecraft.GenerationCenters.resolve(level, client.player.blockPosition().getX(), client.player.blockPosition().getZ());
            var target = current;
            level.getServer().execute(() -> {
                if (current == target && !target.closed) target.tick(Math.floorDiv(center.getX(), 16), Math.floorDiv(center.getZ(), 16), radius);
            });

        } catch (Throwable error) {
            failed = true; pause();
            LodgenConfig.LOGGER.error("Voxy generation stopped because its integration could not initialize", error);
        }
    }

    private static void pause() {
        current = null;
    }

    private static Session session(ServerLevel level) throws ReflectiveOperationException {
        if (level.getServer() == stoppingServer || level.getServer().isStopped()) return null;
        var currentBridge = bridge;
        if (currentBridge == null) return null;
        var context = currentBridge.context(level);
        if (context == null) return null;
        synchronized (SESSIONS) {
            var session = SESSIONS.get(context.engine());
            if (session == null || session.closed) {
                Set<Long> previous = session == null ? Set.of() : Set.copyOf(session.completed);
                session = new Session(level, context, previous);
                SESSIONS.put(context.engine(), session);
            }
            return session;
        }
    }

    /** Called before Voxy stops its saving service. Only already-running
     * conversion holds this lock; native chunk generation never blocks shutdown.
     */
    public static void beforeShutdown() {
        pause();
        dev.lodgen.minecraft.RendererSinks.voxy(null);
        Session[] sessions;
        synchronized (SESSIONS) { sessions = SESSIONS.values().toArray(Session[]::new); }
        for (var session : sessions) session.close();
        bridge = null; failed = false; stoppingServer = null;
    }

    /** The integrated server unloads native chunks before Voxy's engine closes.
     * Stop dispatch and retries first; native futures may fail normally as they
     * drain. Keep completed coverage until engineClosed checkpoints it.
     */
    public static void serverStopping(net.minecraft.server.MinecraftServer server) {
        stoppingServer = server;
        pause();
        Session[] sessions;
        synchronized (SESSIONS) { sessions = SESSIONS.values().toArray(Session[]::new); }
        for (var session : sessions) if (session.level.getServer() == server) session.close();
    }

    public static void engineClosed(Object engine) {
        Session session;
        synchronized (SESSIONS) { session = SESSIONS.remove(engine); }
        if (session == null) return;
        session.close();
        try { TileCoverage.write(session.context.coverageFile(), Set.copyOf(session.completed)); }
        catch (Exception error) { LodgenConfig.LOGGER.error("Cannot save Voxy generation coverage; chunks may regenerate next session", error); }
    }

    private static final class Session implements AutoCloseable {
        final ServerLevel level;
        final VoxyBridge.Context context;
        final ChunkGenerationPipeline pipeline;
        final Set<Long> completed = ConcurrentHashMap.newKeySet();
        final Set<Long> active = ConcurrentHashMap.newKeySet();
        final Map<Long, Long> retry = new ConcurrentHashMap<>();
        final ExecutorService workers;
        final java.util.concurrent.locks.ReentrantReadWriteLock conversionLock = new java.util.concurrent.locks.ReentrantReadWriteLock();
        volatile boolean closed;
        volatile int centerX, centerZ, radius;
        volatile GenerationFrontier frontier;

        Session(ServerLevel level, VoxyBridge.Context context, Set<Long> previous) {
            this.level = level; this.context = context;
            try { completed.addAll(TileCoverage.read(context.coverageFile())); }
            catch (Exception invalid) { LodgenConfig.LOGGER.warn("Ignoring invalid Voxy generation coverage", invalid); }
            completed.addAll(previous);
            pipeline = new ChunkGenerationPipeline(level);
            workers = new dev.lodgen.GenerationWorkers("LODgen Voxy conversion");
            LodgenConfig.LOGGER.info("Normal chunk pipeline active for Voxy in {}", level.dimension());
        }

        void tick(int x, int z, int distance) {
            if (closed) return;
            if (frontier == null || x != centerX || z != centerZ || distance != radius) {
                centerX = x; centerZ = z; radius = distance;
                frontier = new GenerationFrontier(x, z, distance);
            }
            int limit = dev.lodgen.GenerationSettings.current().batches();
            for (var entry : retry.entrySet()) {
                if (active.size() >= limit) return;
                var tile = Tile.fromKey(entry.getKey());
                if (entry.getValue() <= System.nanoTime() && inRange(tile) && retry.remove(entry.getKey(), entry.getValue())) dispatch(tile);
            }
            // Bound the cost of rescanning old terrain when the player moves.
            for (int budget = 0; budget < 256 && active.size() < limit && !frontier.exhausted(); budget++) {
                var tile = frontier.next();
                if (tile != null && !completed.contains(tile.key()) && !active.contains(tile.key()) && !retry.containsKey(tile.key())) dispatch(tile);
            }
        }

        boolean inRange(Tile tile) { return GenerationFrontier.contains(tile, centerX, centerZ, radius); }

        void dispatch(Tile tile) {
            if (!active.add(tile.key())) return;
            var area = new dev.lodgen.generation.GenerationArea(centerX * 16, centerZ * 16, radius, LodgenConfig.INSTANCE.savedChunkRadius());
            pipeline.generate(tile.x(), tile.z(), GenerationFrontier.WIDTH, GenerationFrontier.WIDTH, area, workers, batch -> convert(batch.chunks)).whenComplete((ignored, error) -> {
                        active.remove(tile.key());
                        if (error != null && !closed) {
                            // Retry failures with backoff, never regenerate completed tiles.
                            retry.put(tile.key(), System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
                            LodgenConfig.LOGGER.warn("Voxy generation failed at {},{}; retrying later", tile.x(), tile.z(), error);
                        }
                        if (!closed && current == this && LodgenConfig.INSTANCE.enabled()
                                && !dev.lodgen.minecraft.GenerationTasks.overridesAutomatic(level)) {
                            level.getServer().execute(() -> {
                                if (!closed && current == this) tick(centerX, centerZ, radius);
                            });
                        }
                    });
        }

        CompletableFuture<Void> convert(java.util.List<net.minecraft.world.level.chunk.ChunkAccess> chunks) {
            return CompletableFuture.supplyAsync(() -> {
                if (closed) throw new CancellationException("Voxy session closed");
                return VoxyBridge.snapshot(level, chunks);
            }, level.getServer()).thenAcceptAsync(sections -> {
                conversionLock.readLock().lock();
                try {
                    if (closed) throw new CancellationException("Voxy session closed");
                    try { bridge.ingest(context.engine(), sections); }
                    catch (ReflectiveOperationException error) { throw new java.util.concurrent.CompletionException(error); }
                    var masks = new java.util.HashMap<Long, Integer>();
                    for (var chunk : chunks) {
                        int x = dev.lodgen.minecraft.PersistenceRegistry.x(chunk.getPos()), z = dev.lodgen.minecraft.PersistenceRegistry.z(chunk.getPos());
                        var tile = new Tile(Math.floorDiv(x, 4) * 4, Math.floorDiv(z, 4) * 4);
                        masks.merge(tile.key(), 1 << ((x & 3) + (z & 3) * 4), (a, b) -> a | b);
                    }
                    masks.forEach((key, mask) -> { if (mask == 0xffff) completed.add(key); });
                } finally { conversionLock.readLock().unlock(); }
            }, workers);
        }

        @Override public void close() {
            closed = true;
            pipeline.close(); workers.shutdown();
            // A write-lock barrier waits for readers already ingesting data.
            conversionLock.writeLock().lock();
            conversionLock.writeLock().unlock();
        }
    }
}
