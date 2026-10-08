package dev.ricky12awesome.lodgen.voxy;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class VssNativeStoreTest {
    @Test void nativeCommitReceiptAndReadbackRecoverShedDeposits() throws Exception {
        var store = new NativeWriter(); store.shedOne = true;
        var columns = List.of(new VssNativeStore.Column("world", 1, new byte[]{1}, 10),
                new VssNativeStore.Column("world", 2, new byte[]{2}, 10));
        VssNativeStore.commit(store, columns, () -> true);
        assertArrayEquals(new byte[]{1}, store.committed.get(1L).sectionBytes());
        assertArrayEquals(new byte[]{2}, store.committed.get(2L).sectionBytes());
        assertTrue(store.barriers >= 2);
        assertEquals(new Policy(4096, 17, 9), store.policy);
    }
    @Test void newerNativeEditsWinAndRejectedDepositsFail() throws Exception {
        var store = new NativeWriter();
        store.committed.put(1L, new Hit(new byte[]{9}, 20));
        VssNativeStore.commit(store, List.of(new VssNativeStore.Column("world", 1, new byte[]{1}, 10)), () -> true);
        assertEquals(0, store.deposits);
        assertArrayEquals(new byte[]{9}, store.committed.get(1L).sectionBytes());
        store.reject = true;
        assertThrows(IllegalStateException.class, () -> VssNativeStore.commit(store,
                List.of(new VssNativeStore.Column("world", 2, new byte[]{1}, 10)), () -> true));
    }
    @Test void canceledWorkNeverDepositsAndQueueDrainFailureCannotCheckpointSuccess() {
        var store = new NativeWriter();
        assertThrows(CancellationException.class, () -> VssNativeStore.commit(store,
                List.of(new VssNativeStore.Column("world", 1, new byte[]{1}, 10)), () -> false));
        assertEquals(0, store.deposits);
        store.drain = false;
        assertThrows(IllegalStateException.class, () -> VssNativeStore.flush(store, () -> true));
        assertEquals(0, store.barriers);
    }
    public record Policy(long maxDbBytes, int resweepSeconds, long revision) {}
    public record Hit(byte[] sectionBytes, long columnTimestamp) {}
    public static final class NativeWriter {
        final Map<Long, Hit> committed = new HashMap<>(), pending = new HashMap<>();
        final Policy policy = new Policy(4096, 17, 9);
        boolean shedOne, reject, drain = true;
        int deposits, barriers;
        public Hit get(String dimension, long pos) { return committed.get(pos); }
        public boolean deposit(String dimension, long pos, byte[] bytes, long stamp, long acquired) {
            if (reject) return false;
            deposits++;
            if (shedOne) { shedOne = false; return true; }
            pending.put(pos, new Hit(bytes, stamp)); return true;
        }
        public boolean awaitDepositQueueEmpty(long timeout) { return drain; }
        public Policy adoptedPolicy() { return policy; }
        public CompletableFuture<Void> updatePolicy(long cap, int interval, long revision) {
            assertEquals(policy, new Policy(cap, interval, revision));
            barriers++; committed.putAll(pending); pending.clear();
            return CompletableFuture.completedFuture(null);
        }
    }
}
