package dev.lodgen.minecraft;

import dev.lodgen.generation.SavePolicy;
import dev.lodgen.generation.GenerationScope;
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
    private static final Map<ServerLevel, SavePolicy> LEVELS = new IdentityHashMap<>();
    private static final Map<ResourceKey<Level>, SavePolicy> STORAGE = new HashMap<>();
    private static final Map<Object, SavePolicy> TICKETS = new IdentityHashMap<>();
    private static final Map<ServerLevel, NormalChunkBackend> BACKENDS = new IdentityHashMap<>();
    // OpenCL batches can expand the vanilla dependency footprint by up to 3 chunks.
    public static final int BATCH_PADDING = 4;

    public static synchronized void register(ServerLevel level, Object tickets) {
        SavePolicy policy = LEVELS.computeIfAbsent(level, ignored -> new SavePolicy());
        STORAGE.put(level.dimension(), policy);
        TICKETS.put(tickets, policy);
    }

    public static synchronized SavePolicy get(ServerLevel level) { return LEVELS.get(level); }
    public static boolean isIntegratedServer(Object level) {
        return level instanceof ServerLevel serverLevel && !serverLevel.getServer().isDedicatedServer();
    }
    public static boolean suppressUpdates(Object level, int x, int z) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        SavePolicy policy = get(serverLevel);
        return policy != null && policy.suppress(x, z);
    }
    public static synchronized SavePolicy get(RegionStorageInfo info) { return STORAGE.get(info.dimension()); }
    public static synchronized NormalChunkBackend backend(Object level) {
        return BACKENDS.computeIfAbsent((ServerLevel) level, NormalChunkBackend::new);
    }

    public static synchronized void normalTicket(Object owner, long pos, Ticket ticket) {
        SavePolicy policy = TICKETS.get(owner);
        if (policy != null && ticket.getType() != NormalChunkBackend.TICKET) {
            // Only a nested vanilla lookup inherits transient ownership. Explicit
            // player, portal, forced and mod tickets always retain normal saves.
            if (ticket.getType() == TicketType.UNKNOWN && GenerationScope.isTransient(policy)) return;
            if (TRACE_OWNERSHIP && policy.suppress((int) pos, (int) (pos >> 32))) {
                dev.lodgen.LodgenConfig.LOGGER.warn("Ownership promoted by ticket {} at {},{}", ticket, (int) pos, (int) (pos >> 32), new Exception("Ticket caller"));
            }
            policy.promoteArea((int) pos, (int) (pos >> 32), Math.max(0, ChunkLevel.MAX_LEVEL - ticket.getTicketLevel()) + BATCH_PADDING);
        }
    }

    public static void normalRequest(ServerLevel level, int x, int z) {
        SavePolicy policy = get(level);
        if (policy != null && !GenerationScope.isTransient(policy)) {
            if (TRACE_OWNERSHIP && policy.suppress(x, z)) {
                dev.lodgen.LodgenConfig.LOGGER.warn("Ownership promoted by request at {},{}", x, z, new Exception("Request caller"));
            }
            policy.normalRequest(x, z, NormalChunkBackend.DEPENDENCY_RADIUS);
        }
    }

    public static boolean suppress(SavePolicy policy, ChunkPos pos) { return policy != null && policy.suppress(x(pos), z(pos)); }

    public static synchronized void close(ServerLevel level) {
        SavePolicy policy = LEVELS.remove(level);
        STORAGE.remove(level.dimension(), policy);
        TICKETS.values().removeIf(value -> value == policy);
        BACKENDS.remove(level);
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
    public static long pack(ChunkPos pos) { return (x(pos) & 0xffffffffL) | (long) z(pos) << 32; }
}
