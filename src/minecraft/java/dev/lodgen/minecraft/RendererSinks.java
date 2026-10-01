package dev.lodgen.minecraft;

import dev.lodgen.ModSupport;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ConcurrentHashMap;

/** Common task code never loads client renderer classes on a dedicated server. */
public final class RendererSinks {
    public interface VoxySink {
        boolean ready(ServerLevel level);
        default int radius(ServerLevel level) { return 0; }
        CompletableFuture<Void> convert(ServerLevel level, List<ChunkAccess> chunks);
    }
    private static volatile VoxySink voxy;
    public static void voxy(VoxySink sink) { voxy = sink; }
    public static boolean dhAvailable() { return ModSupport.loaded("distanthorizons"); }
    public static boolean voxyAvailable() { return ModSupport.loaded("voxy") || ModSupport.loaded("roxy"); }
    public static boolean ready(ServerLevel level, boolean useDh, boolean useVoxy) {
        return (!useDh || dhAvailable() && DhTaskSink.ready(level))
                && (!useVoxy || voxy != null && voxy.ready(level));
    }
    public static CompletableFuture<Void> convert(ServerLevel level, List<ChunkAccess> chunks, Executor workers, boolean useDh, boolean useVoxy) {
        if (chunks.isEmpty()) return CompletableFuture.completedFuture(null);
        var dh = useDh ? DhTaskSink.convert(level, chunks, workers) : CompletableFuture.<Void>completedFuture(null);
        var sink = voxy;
        var vx = !useVoxy ? CompletableFuture.<Void>completedFuture(null)
                : sink == null ? CompletableFuture.<Void>failedFuture(new java.util.concurrent.CancellationException("Voxy is shutting down"))
                : sink.convert(level, chunks);
        return CompletableFuture.allOf(dh, vx);
    }
    public static int voxyRadius(ServerLevel level) { var sink = voxy; return sink == null ? 0 : sink.radius(level); }
    public static int dhRadius() { return dhAvailable() ? DhTaskSink.radius() : 0; }
}
