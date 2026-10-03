package dev.lodgen.voxy;

import dev.lodgen.LodgenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.server.level.ServerLevel;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/** Voxy output lifecycle and conversion. GenerationTasks owns all automatic
 * and command generation; this adapter never launches native chunk requests.
 */
public final class VoxyGeneration {
    private static final Map<Object, Session> SESSIONS = new IdentityHashMap<>();
    private static volatile net.minecraft.server.MinecraftServer stoppingServer;
    private static VoxyBridge bridge;
    private static boolean failed;

    public static void tick(Minecraft client) {
        if (failed) return;
        if (client.level == null || client.player == null || client.getSingleplayerServer() == null) {
            return;
        }
        if (client.getSingleplayerServer() == stoppingServer || client.getSingleplayerServer().isStopped()) {
            return;
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
            if (context == null) return;
            var level = client.getSingleplayerServer().getLevel(client.level.dimension());
            if (level == null) return;
        } catch (Throwable error) {
            failed = true;
            LodgenConfig.LOGGER.error("Voxy generation stopped because its integration could not initialize", error);
        }
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
                session = new Session(level, context);
                SESSIONS.put(context.engine(), session);
            }
            return session;
        }
    }

    /** Called before Voxy stops its saving service. Only already-running
     * conversion holds this lock; native chunk generation never blocks shutdown.
     */
    public static void beforeShutdown() {
        dev.lodgen.minecraft.RendererSinks.voxy(null);
        Session[] sessions;
        synchronized (SESSIONS) { sessions = SESSIONS.values().toArray(Session[]::new); }
        for (var session : sessions) session.close();
        bridge = null; failed = false; stoppingServer = null;
    }

    /** The integrated server unloads native chunks before Voxy's engine closes.
     * Close conversion sessions first; native futures may fail normally as they
     * drain. GenerationTasks stops dispatch and checkpoints completed batches.
     */
    public static void serverStopping(net.minecraft.server.MinecraftServer server) {
        stoppingServer = server;
        Session[] sessions;
        synchronized (SESSIONS) { sessions = SESSIONS.values().toArray(Session[]::new); }
        for (var session : sessions) if (session.level.getServer() == server) session.close();
    }

    public static void engineClosed(Object engine) {
        Session session;
        synchronized (SESSIONS) { session = SESSIONS.remove(engine); }
        if (session == null) return;
        session.close();
    }

    private static final class Session implements AutoCloseable {
        final ServerLevel level;
        final VoxyBridge.Context context;
        final ExecutorService workers;
        final java.util.concurrent.locks.ReentrantReadWriteLock conversionLock = new java.util.concurrent.locks.ReentrantReadWriteLock();
        volatile boolean closed;

        Session(ServerLevel level, VoxyBridge.Context context) {
            this.level = level; this.context = context;
            workers = new dev.lodgen.GenerationWorkers("LODgen Voxy conversion");
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
                } finally { conversionLock.readLock().unlock(); }
            }, workers);
        }

        @Override public void close() {
            closed = true;
            workers.shutdown();
            // A write-lock barrier waits for readers already ingesting data.
            conversionLock.writeLock().lock();
            conversionLock.writeLock().unlock();
        }
    }
}
