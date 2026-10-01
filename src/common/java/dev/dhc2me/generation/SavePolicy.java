package dev.dhc2me.generation;

import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

/** Per-server-session ownership. Promotion is irreversible, including after unload.
 * Keep compact region masks until the level closes: removing them when a DH
 * request completes would let delayed POI/entity/unload writes escape.
 */
public final class SavePolicy {
    private final Map<Long, Region> regions = new HashMap<>();

    public synchronized void claim(int x, int z, boolean alreadyLoaded) {
        Region region = regions.computeIfAbsent(regionKey(x, z), ignored -> new Region());
        int index = index(x, z);
        if (alreadyLoaded && !region.ephemeral.get(index)) region.permanent.set(index);
        if (!region.permanent.get(index)) region.ephemeral.set(index);
    }

    public synchronized boolean suppress(int x, int z) {
        Region region = regions.get(regionKey(x, z));
        return region != null && region.ephemeral.get(index(x, z));
    }

    public synchronized void promote(int x, int z) {
        // Also record requests preceding a claim, before their holders exist.
        Region region = regions.computeIfAbsent(regionKey(x, z), ignored -> new Region());
        int index = index(x, z);
        region.ephemeral.clear(index);
        region.permanent.set(index);
    }

    public synchronized void promoteArea(int x, int z, int radius) {
        int minX = x - radius, maxX = x + radius, minZ = z - radius, maxZ = z + radius;
        for (int rx = minX >> 5; rx <= maxX >> 5; rx++) {
            for (int rz = minZ >> 5; rz <= maxZ >> 5; rz++) {
                Region region = regions.computeIfAbsent(regionKey(rx << 5, rz << 5), ignored -> new Region());
                int firstX = Math.max(minX, rx << 5) & 31, lastX = Math.min(maxX, (rx << 5) + 31) & 31;
                int firstZ = Math.max(minZ, rz << 5) & 31, lastZ = Math.min(maxZ, (rz << 5) + 31) & 31;
                for (int row = firstZ; row <= lastZ; row++) {
                    int from = row * 32 + firstX, to = row * 32 + lastX + 1;
                    region.ephemeral.clear(from, to);
                    region.permanent.set(from, to);
                }
            }
        }
    }

    public synchronized void normalRequest(int x, int z, int radius) {
        Region region = regions.computeIfAbsent(regionKey(x, z), ignored -> new Region());
        if (!region.requested.get(index(x, z))) {
            region.requested.set(index(x, z));
            promoteArea(x, z, radius);
        }
    }

    private static long regionKey(int x, int z) { return (x >> 5 & 0xffffffffL) | (long) (z >> 5) << 32; }
    private static int index(int x, int z) { return (x & 31) | (z & 31) << 5; }
    private static final class Region {
        private final BitSet ephemeral = new BitSet(1024);
        private final BitSet permanent = new BitSet(1024);
        private final BitSet requested = new BitSet(1024);
    }
}
