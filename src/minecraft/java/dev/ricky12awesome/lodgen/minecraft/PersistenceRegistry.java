package dev.ricky12awesome.lodgen.minecraft;

import dev.ricky12awesome.lodgen.generation.SavePolicy;
import dev.ricky12awesome.lodgen.generation.GenerationScope;
import dev.ricky12awesome.lodgen.generation.ChunkThroughput;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ChunkLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.Ticket;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;

import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.Map;

/** Minecraft adapters for the shared ownership policy. No C2ME generation API. */
public final class PersistenceRegistry {
    private static final boolean TRACE_OWNERSHIP = Boolean.getBoolean("lodgen.test.traceOwnership");
    private static final Map<ServerLevel, State> LEVELS = new IdentityHashMap<>();
    private static final Map<ResourceKey<Level>, State> STORAGE = new HashMap<>();
    private static final Map<Object, State> TICKETS = new IdentityHashMap<>();
    // OpenCL batches can expand the vanilla dependency footprint by up to 3 chunks.
    public static final int BATCH_PADDING = 4;

    public static synchronized void register(ServerLevel level, Object tickets) {
        State state = LEVELS.computeIfAbsent(level, ignored -> new State());
        if (state.policy == null) state.policy = new SavePolicy(LodGenerationWorld.constructing());
        STORAGE.put(level.dimension(), state);
        TICKETS.put(tickets, state);
    }

    public static synchronized SavePolicy get(ServerLevel level) {
        State state = LEVELS.get(level);
        return state == null ? null : state.policy;
    }
    public static boolean isIntegratedServer(Object level) {
        return level instanceof ServerLevel serverLevel && !serverLevel.getServer().isDedicatedServer();
    }
    public static boolean suppressUpdates(Object level, int x, int z) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        SavePolicy policy = get(serverLevel);
        return ChunkPersistence.suppress(policy, x, z);
    }
    public static synchronized SavePolicy get(RegionStorageInfo info) {
        State state = STORAGE.get(info.dimension());
        return state == null ? null : state.policy;
    }
    public static synchronized NormalChunkBackend backend(Object level) {
        State state = LEVELS.get((ServerLevel) level);
        if (state == null || state.policy == null) throw new IllegalStateException("Chunk persistence hooks did not initialize");
        if (state.backend == null) state.backend = new NormalChunkBackend(level);
        return state.backend;
    }

    public static synchronized ChunkThroughput throughput(Object level) {
        State state = LEVELS.computeIfAbsent((ServerLevel) level, ignored -> new State());
        if (state.throughput == null) state.throughput = new ChunkThroughput();
        return state.throughput;
    }

    public static synchronized void normalTicket(Object owner, long pos, Ticket ticket) {
        State state = TICKETS.get(owner);
        SavePolicy policy = state == null ? null : state.policy;
        if (policy != null && ticket.getType() != NormalChunkBackend.TICKET) {
            // Only a nested vanilla lookup inherits transient ownership. Explicit
            // player, portal, forced and mod tickets always retain normal saves.
            if (ticket.getType() == TicketType.UNKNOWN && GenerationScope.isTransient(policy)) return;
            if (TRACE_OWNERSHIP && policy.suppress((int) pos, (int) (pos >> 32))) {
                dev.ricky12awesome.lodgen.LodgenConfig.LOGGER.warn("Ownership promoted by ticket {} at {},{}", ticket, (int) pos, (int) (pos >> 32), new Exception("Ticket caller"));
            }
            policy.promoteArea((int) pos, (int) (pos >> 32), Math.max(0, ChunkLevel.MAX_LEVEL - ticket.getTicketLevel()) + BATCH_PADDING);
        }
    }

    public static void normalRequest(ServerLevel level, int x, int z) {
        SavePolicy policy = get(level);
        if (policy != null && !GenerationScope.isTransient(policy)) {
            if (TRACE_OWNERSHIP && policy.suppress(x, z)) {
                dev.ricky12awesome.lodgen.LodgenConfig.LOGGER.warn("Ownership promoted by request at {},{}", x, z, new Exception("Request caller"));
            }
            policy.normalRequest(x, z, NormalChunkBackend.DEPENDENCY_RADIUS);
        }
    }

    public static boolean suppress(SavePolicy policy, ChunkPos pos) { return ChunkPersistence.suppress(policy, pos); }

    public static synchronized void close(ServerLevel level) {
        State state = LEVELS.remove(level);
        if (state == null) return;
        STORAGE.remove(level.dimension(), state);
        TICKETS.values().removeIf(value -> value == state);
    }

    public static int x(ChunkPos pos) {
        // #if MC_1211
        return pos.x;
        // #else
        return pos.x();
        // #endif
    }
    public static int z(ChunkPos pos) {
        // #if MC_1211
        return pos.z;
        // #else
        return pos.z();
        // #endif
    }
    public static long pack(ChunkPos pos) { return pack(x(pos), z(pos)); }
    public static long pack(int x, int z) { return (x & 0xffffffffL) | (long) z << 32; }
    public static int minY(net.minecraft.world.level.chunk.ChunkAccess chunk) {
        // #if MC_1211
        return chunk.getMinBuildHeight();
        // #else
        return chunk.getMinY();
        // #endif
    }

    private static final class State {
        SavePolicy policy;
        NormalChunkBackend backend;
        ChunkThroughput throughput;
    }
}
