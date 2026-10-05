package dev.ricky12awesome.lodgen.generation;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/** Amortize DH's full pending-map scan across nearby dispatches. Revalidate
 * identity and bounds at selection; refresh on movement, growth or 64 picks.
 */
public final class SpatialTaskIndex<K, V> {
    private final PriorityQueue<Candidate<K, V>> pending = new PriorityQueue<>(Comparator.comparingLong(Candidate::priority));
    private Object context;
    private int selections, expectedSize;

    public synchronized <T> T select(ConcurrentMap<K, V> tasks, Object area,
                                    Function<Map.Entry<K, V>, SpatialGenerationOrder.Ranked<T>> rank) {
        if (pending.isEmpty() || !Objects.equals(context, area) || selections >= 64 || tasks.size() > expectedSize + 64) {
            rebuild(tasks, area, rank);
        }
        selections++;
        for (int attempt = 0; attempt < 2; attempt++) {
            while (!pending.isEmpty()) {
                var entry = pending.remove().entry();
                if (tasks.get(entry.getKey()) != entry.getValue()) continue;
                var value = rank.apply(entry);
                if (value == null) continue;
                expectedSize = Math.max(0, tasks.size() - 1);
                return value.value();
            }
            if (attempt == 0 && !tasks.isEmpty()) rebuild(tasks, area, rank);
        }
        return null;
    }
    private <T> void rebuild(ConcurrentMap<K, V> tasks, Object area,
                             Function<Map.Entry<K, V>, SpatialGenerationOrder.Ranked<T>> rank) {
        pending.clear(); context = area; selections = 0;
        for (var entry : tasks.entrySet()) {
            var value = rank.apply(entry);
            if (value != null) pending.add(new Candidate<>(entry, value.priority()));
        }
        expectedSize = tasks.size();
    }
    public synchronized void clear() { pending.clear(); context = null; }
    private record Candidate<K, V>(Map.Entry<K, V> entry, long priority) {}
}
