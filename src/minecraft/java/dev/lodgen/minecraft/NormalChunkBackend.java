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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** Normal asynchronous FULL generation with shared tickets. Coalesce admissions
 * and releases before updating distances, instead of doing that twice per tile.
 */
public final class NormalChunkBackend {
    // #if MC_1211
    public static final TicketType<ChunkPos> TICKET = TicketType.create("lodgen_features", Comparator.comparingLong(PersistenceRegistry::pack));
    // #else
    public static final TicketType TICKET = new TicketType(0L, TicketType.FLAG_LOADING);
    // #endif
    public static final int DEPENDENCY_RADIUS = ChunkLevel.MAX_LEVEL - ChunkLevel.byStatus(ChunkStatus.FULL) + PersistenceRegistry.BATCH_PADDING;
    private final ServerLevel level;
    private final SavePolicy policy;
    private final Map<Long, Integer> references = new HashMap<>();
    private final ConcurrentLinkedQueue<Request> pending = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Batch> retiring = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean scheduled = new AtomicBoolean();

    public NormalChunkBackend(Object level) {
        this.level = (ServerLevel) level;
        this.policy = PersistenceRegistry.get(this.level);
        if (policy == null) throw new IllegalStateException("Chunk persistence hooks did not initialize");
    }
    public CompletableFuture<Batch> request(int x, int z, int width) {
        return request(x, z, width, width, GenerationCenters.automatic(level, LodgenConfig.INSTANCE.generationDistance()));
    }
    public CompletableFuture<Batch> request(int x, int z, int width, int height, dev.lodgen.generation.GenerationArea area) {
        return request(x, z, width, height, area, (cx, cz) -> true);
    }
    public CompletableFuture<Batch> request(int x, int z, int width, int height, dev.lodgen.generation.GenerationArea area,
                                           java.util.function.BiPredicate<Integer, Integer> include) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("Empty native batch");
        var request = new Request(x, z, width, height, area, include);
        pending.add(request); schedule();
        return request.result;
    }
    private void schedule() {
        if (scheduled.compareAndSet(false, true)) level.getServer().execute(this::drain);
    }
    private void drain() {
        var cache = level.getChunkSource();
        var map = cache.chunkMap;
        var released = new ArrayList<Batch>();
        var admitted = new ArrayList<Request>();
        try {
            Batch batch;
            while ((batch = retiring.poll()) != null) {
                released.add(batch);
                for (var pos : batch.positions) {
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
            }
            Request request;
            // Bound one drain so a large heap does not monopolize a server tick.
            while (admitted.size() < 256 && (request = pending.poll()) != null) {
                admitted.add(request);
                try {
                    policy.claimArea(request.x - DEPENDENCY_RADIUS, request.z - DEPENDENCY_RADIUS,
                            request.width + 2 * DEPENDENCY_RADIUS, request.height + 2 * DEPENDENCY_RADIUS,
                            key -> ((ChunkMapAccess) map).lodgen$holder(key) != null);
                    for (int x = request.x; x < request.x + request.width; x++) for (int z = request.z; z < request.z + request.height; z++) {
                        if (!request.include.test(x, z)) continue;
                        if (request.area != null && request.area.saves(x, z)) policy.saveGenerated(x, z);
                        var pos = new ChunkPos(x, z);
                        request.batch.positions.add(pos);
                        if (references.merge(PersistenceRegistry.pack(pos), 1, Integer::sum) == 1) {
                            // #if MC_1211
                            cache.addRegionTicket(TICKET, pos, 0, pos);
                            // #else
                            cache.addTicketWithRadius(TICKET, pos, 0);
                            // #endif
                        }
                    }
                } catch (Throwable error) { request.failure = error; }
            }
            ((ChunkCacheAccess) cache).lodgen$updateDistances();
            for (var requestToStart : admitted) {
                if (requestToStart.failure != null) { requestToStart.fail(requestToStart.failure); continue; }
                var futures = new ArrayList<CompletableFuture<ChunkAccess>>();
                try {
                    for (var pos : requestToStart.batch.positions) {
                        var holder = ((ChunkMapAccess) map).lodgen$holder(PersistenceRegistry.pack(pos));
                        if (holder == null) throw new IllegalStateException("Missing chunk holder at " + pos);
                        futures.add(holder.scheduleChunkGenerationTask(ChunkStatus.FULL, map)
                                .thenApply(result -> result.orElseThrow(() -> new IllegalStateException(result.getError()))));
                    }
                    CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                        if (error != null) requestToStart.fail(error);
                        else {
                            futures.forEach(future -> requestToStart.batch.chunks.add(future.join()));
                            requestToStart.result.complete(requestToStart.batch);
                        }
                    });
                } catch (Throwable error) { requestToStart.fail(error); }
            }
            for (var removed : released) removed.released.complete(null);
        } catch (Throwable error) {
            for (var request : admitted) request.fail(error);
            for (var removed : released) removed.released.completeExceptionally(error);
        } finally {
            scheduled.set(false);
            if (!pending.isEmpty() || !retiring.isEmpty()) schedule();
        }
    }
    private final class Request {
        final int x, z, width, height;
        final dev.lodgen.generation.GenerationArea area;
        final java.util.function.BiPredicate<Integer, Integer> include;
        final Batch batch = new Batch();
        final CompletableFuture<Batch> result = new CompletableFuture<>();
        Throwable failure;
        Request(int x, int z, int width, int height, dev.lodgen.generation.GenerationArea area, java.util.function.BiPredicate<Integer, Integer> include) {
            this.x = x; this.z = z; this.width = width; this.height = height; this.area = area;
            this.include = include;
        }
        void fail(Throwable error) {
            batch.release().whenComplete((ignored, releaseError) -> result.completeExceptionally(error));
        }
    }
    public final class Batch {
        public ServerLevel sourceLevel() { return level; }
        private final ArrayList<ChunkPos> positions = new ArrayList<>();
        public final ArrayList<ChunkAccess> chunks = new ArrayList<>();
        private CompletableFuture<Void> released;
        public synchronized CompletableFuture<Void> release() {
            if (released == null) {
                released = new CompletableFuture<>();
                retiring.add(this); schedule();
            }
            return released;
        }
    }
}
