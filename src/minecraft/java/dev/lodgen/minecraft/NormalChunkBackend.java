package dev.lodgen.minecraft;

import dev.lodgen.generation.SavePolicy;
import dev.lodgen.LodgenConfig;
import dev.lodgen.mixin.ChunkCacheAccess;
import dev.lodgen.mixin.ChunkMapAccess;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Uses the same ticket -> holder -> FULL pipeline as Chunky. C2ME/OpenCL
 * replace this pipeline themselves. Tickets remain alive until LOD conversion
 * finishes, and overlapping DH requests share reference-counted tickets.
 */
public final class NormalChunkBackend {
    // #if MC_1211
    public static final TicketType<ChunkPos> TICKET = TicketType.create("lodgen_features", Comparator.comparingLong(PersistenceRegistry::pack));
    // #else
    // TicketStorage compares types by identity, so this is distinct from other loaders.
    public static final TicketType TICKET = new TicketType(0L, TicketType.FLAG_LOADING);
    // #endif
    public static final int DEPENDENCY_RADIUS = ChunkLevel.MAX_LEVEL - ChunkLevel.byStatus(ChunkStatus.FULL) + PersistenceRegistry.BATCH_PADDING;

    private final ServerLevel level;
    private final SavePolicy policy;
    private final Map<Long, Integer> references = new HashMap<>();

    public NormalChunkBackend(Object level) {
        this.level = (ServerLevel) level;
        this.policy = PersistenceRegistry.get(this.level);
        if (policy == null) throw new IllegalStateException("Chunk persistence hooks did not initialize");
    }

    public CompletableFuture<Batch> request(int minX, int minZ, int width) {
        return request(minX, minZ, width, width, GenerationCenters.automatic(level, LodgenConfig.INSTANCE.generationDistance()));
    }

    public CompletableFuture<Batch> request(int minX, int minZ, int width, int height, dev.lodgen.generation.GenerationArea area) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Empty native batch");
        return CompletableFuture.supplyAsync(() -> {
            var cache = level.getChunkSource();
            var map = cache.chunkMap;
            // Claim the entire dependency area before any generation or IO starts.
            for (int x = minX - DEPENDENCY_RADIUS; x < minX + width + DEPENDENCY_RADIUS; x++) {
                for (int z = minZ - DEPENDENCY_RADIUS; z < minZ + height + DEPENDENCY_RADIUS; z++) {
                    long key = (x & 0xffffffffL) | (long) z << 32;
                    policy.claim(x, z, ((ChunkMapAccess) map).lodgen$holder(key) != null);
                }
            }
            ArrayList<ChunkPos> positions = new ArrayList<>(width * height);
            Batch batch = new Batch(positions);
            ArrayList<CompletableFuture<ChunkAccess>> futures = new ArrayList<>(width * height);
            try {
                for (int x = minX; x < minX + width; x++) {
                    for (int z = minZ; z < minZ + height; z++) {
                        if (area != null && area.saves(x, z)) policy.saveGenerated(x, z);
                        var pos = new ChunkPos(x, z);
                        positions.add(pos);
                        long key = PersistenceRegistry.pack(pos);
                        if (references.merge(key, 1, Integer::sum) == 1) {
                            // #if MC_1211
                            cache.addRegionTicket(TICKET, pos, 0, pos);
                            // #else
                            cache.addTicketWithRadius(TICKET, pos, 0);
                            // #endif
                        }
                    }
                }
                ((ChunkCacheAccess) cache).lodgen$updateDistances();
                for (ChunkPos pos : positions) {
                    var holder = ((ChunkMapAccess) map).lodgen$holder(PersistenceRegistry.pack(pos));
                    if (holder == null) throw new IllegalStateException("Missing chunk holder at " + pos);
                    futures.add(holder.scheduleChunkGenerationTask(ChunkStatus.FULL, map)
                            .thenApply(result -> result.orElseThrow(() -> new IllegalStateException(result.getError()))));
                }
            } catch (Throwable error) {
                batch.release();
                throw error;
            }
            return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).handle((ignored, error) -> {
                if (error != null) {
                    batch.release();
                    throw new java.util.concurrent.CompletionException(error);
                }
                futures.forEach(future -> batch.chunks.add(future.join()));
                return batch;
            });
        }, level.getServer()).thenCompose(Function.identity());
    }

    public final class Batch {
        private final ArrayList<ChunkPos> positions;
        public final ArrayList<ChunkAccess> chunks = new ArrayList<>();
        private Batch(ArrayList<ChunkPos> positions) { this.positions = positions; }
        private CompletableFuture<Void> released;
        public synchronized CompletableFuture<Void> release() {
            if (released != null) return released;
            released = CompletableFuture.runAsync(() -> {
                var cache = level.getChunkSource();
                for (ChunkPos pos : positions) {
                    long key = PersistenceRegistry.pack(pos);
                    Integer remaining = references.computeIfPresent(key, (ignored, count) -> count == 1 ? null : count - 1);
                    if (remaining == null) {
                        // #if MC_1211
                        cache.removeRegionTicket(TICKET, pos, 0, pos);
                        // #else
                        cache.removeTicketWithRadius(TICKET, pos, 0);
                        // #endif
                    }
                }
                ((ChunkCacheAccess) cache).lodgen$updateDistances();
            }, level.getServer());
            return released;
        }
    }
}
