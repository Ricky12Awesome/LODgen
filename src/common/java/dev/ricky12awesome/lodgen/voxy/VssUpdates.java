package dev.ricky12awesome.lodgen.voxy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** New terrain needs cache invalidation and a client refresh, while retaining its bytes. */
public final class VssUpdates {
    public record Batch(String dimension, long[] positions) {}
    private final Map<String, LinkedHashSet<Long>> pending = new LinkedHashMap<>();
    // Arrays use identity equality. VSS owns the array until its mailbox event is done,
    // including retries; removing a tag in its completion callback would break replay.
    private final Map<long[], String> announcements = new WeakHashMap<>();

    public synchronized void committed(List<VssColumns.Column> columns) {
        for (var column : columns)
            pending.computeIfAbsent(column.dimension(), ignored -> new LinkedHashSet<>()).add(column.position());
    }

    public synchronized List<Batch> drain() {
        var batches = new ArrayList<Batch>();
        for (var entry : pending.entrySet()) {
            long[] positions = entry.getValue().stream().mapToLong(Long::longValue).toArray();
            announcements.put(positions, entry.getKey());
            batches.add(new Batch(entry.getKey(), positions));
        }
        pending.clear();
        return batches;
    }

    public synchronized boolean preserves(String dimension, long[] positions) {
        return dimension.equals(announcements.get(positions));
    }

    /** Match VSS's square distance gate and respect the wire packet's position limit. */
    public static List<long[]> inRange(long[] positions, int x, int z, int radius, int limit) {
        if (limit <= 0) throw new IllegalArgumentException("Invalid VSS packet limit");
        var result = new ArrayList<long[]>();
        long[] page = new long[Math.min(positions.length, limit)];
        int count = 0;
        for (long position : positions) {
            if (Math.abs((long) (int) (position >> 32) - x) > radius
                    || Math.abs((long) (int) position - z) > radius) continue;
            page[count++] = position;
            if (count == page.length) {
                result.add(page);
                page = new long[Math.min(positions.length, limit)];
                count = 0;
            }
        }
        if (count > 0) result.add(java.util.Arrays.copyOf(page, count));
        return result;
    }
}
