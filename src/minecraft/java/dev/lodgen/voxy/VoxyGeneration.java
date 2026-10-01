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
import java.util.concurrent.Executors;

/** Voxy has no generation queue. This scheduler drives the shared native path
 * around the local player, maintaining a completed frontier for each engine.
 */
public final class VoxyGeneration {
    private static final Map<Object, Session> SESSIONS = new IdentityHashMap<>();
    private static Session current;
    private static VoxyBridge bridge;
    private static boolean failed;

    public static void tick(Minecraft client) {
        if (failed) return;
        if (!LodgenConfig.INSTANCE.enabled() || client.level == null || client.player == null || client.getSingleplayerServer() == null) {
            pause(); return;
        }
        try {
            if (bridge == null) bridge = new VoxyBridge();
            var context = bridge.context(client.level);
            if (context == null) { pause(); return; }
            var level = client.getSingleplayerServer().getLevel(client.level.dimension());
            if (level == null) return;
            if (current == null || current.context.engine() != context.engine() || current.level != level) {
                pause();
                synchronized (SESSIONS) {
                    current = SESSIONS.get(context.engine());
                    if (current == null || current.closed) {
                        Set<Long> previous = current == null ? Set.of() : Set.copyOf(current.completed);
                        current = new Session(level, context, previous);
                        SESSIONS.put(context.engine(), current);
                    }
                }
            }
            int radius = LodgenConfig.INSTANCE.generationDistance() > 0 ? LodgenConfig.INSTANCE.generationDistance() : context.radius();
            var pos = client.player.chunkPosition();
            current.tick(dev.lodgen.minecraft.PersistenceRegistry.x(pos), dev.lodgen.minecraft.PersistenceRegistry.z(pos), radius);
        } catch (Throwable error) {
            failed = true; pause();
            LodgenConfig.LOGGER.error("Voxy generation stopped because its integration could not initialize", error);
        }
    }

    private static void pause() {
        if (current != null) { current.close(); current = null; }
    }

    /** Called before Voxy stops its saving service. Only already-running
     * conversion holds this lock; native chunk generation never blocks shutdown.
     */
    public static void beforeShutdown() {
        pause();
        Session[] sessions;
        synchronized (SESSIONS) { sessions = SESSIONS.values().toArray(Session[]::new); }
        for (var session : sessions) session.close();
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
        GenerationFrontier frontier;

        Session(ServerLevel level, VoxyBridge.Context context, Set<Long> previous) {
            this.level = level; this.context = context;
            try { completed.addAll(TileCoverage.read(context.coverageFile())); }
            catch (Exception invalid) { LodgenConfig.LOGGER.warn("Ignoring invalid Voxy generation coverage", invalid); }
            completed.addAll(previous);
            pipeline = new ChunkGenerationPipeline(level);
            workers = Executors.newFixedThreadPool(Math.max(1, Math.min(8, Runtime.getRuntime().availableProcessors() / 2)), task -> {
                Thread thread = new Thread(task, "LODgen Voxy conversion"); thread.setDaemon(true); return thread;
            });
            LodgenConfig.LOGGER.info("Normal chunk pipeline active for Voxy in {}", level.dimension());
        }

        void tick(int x, int z, int distance) {
            if (closed) return;
            if (frontier == null || x != centerX || z != centerZ || distance != radius) {
                centerX = x; centerZ = z; radius = distance;
                frontier = new GenerationFrontier(x, z, distance);
            }
            int limit = LodgenConfig.INSTANCE.pipelineBatches();
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
            pipeline.generate(tile.x(), tile.z(), GenerationFrontier.WIDTH, workers, batch ->
                    CompletableFuture.supplyAsync(() -> {
                        if (closed) throw new CancellationException("Voxy session closed");
                        return VoxyBridge.snapshot(level, batch.chunks);
                    }, level.getServer()).thenAcceptAsync(sections -> {
                        conversionLock.readLock().lock();
                        try {
                            if (closed) throw new CancellationException("Voxy session closed");
                            try { bridge.ingest(context.engine(), sections); completed.add(tile.key()); }
                            catch (ReflectiveOperationException error) { throw new java.util.concurrent.CompletionException(error); }
                        } finally { conversionLock.readLock().unlock(); }
                    }, workers)).whenComplete((ignored, error) -> {
                        active.remove(tile.key());
                        if (error != null && !closed) {
                            // Retry failures with backoff, never regenerate completed tiles.
                            retry.put(tile.key(), System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10));
                            LodgenConfig.LOGGER.warn("Voxy generation failed at {},{}; retrying later", tile.x(), tile.z(), error);
                        }
                    });
        }

        @Override public void close() {
            conversionLock.writeLock().lock();
            try { closed = true; }
            finally { conversionLock.writeLock().unlock(); }
            pipeline.close(); workers.shutdown();
        }
    }
}
