package dev.ricky12awesome.lodgen.voxy;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Worker-only deposit pacing and receipts using the native VSS writer and readers. */
public final class VssNativeStore {
    public record Column(String dimension, long position, byte[] bytes, long stamp) {}
    private VssNativeStore() {}

    public static void commit(Object store, List<Column> columns, BooleanSupplier active) throws Exception {
        for (int offset = 0; offset < columns.size(); offset += 64) {
            List<Column> batch = columns.subList(offset, Math.min(columns.size(), offset + 64));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            for (;;) {
                check(active);
                for (var column : batch) {
                    if (present(store, column)) continue;
                    if (!(boolean) invoke(store, "deposit", new Class<?>[]{String.class, long.class, byte[].class, long.class, long.class},
                            column.dimension(), column.position(), column.bytes(), column.stamp(), column.stamp()))
                        throw new IllegalStateException("Native VSS cache rejected generated column");
                }
                flush(store, active);
                // VSS keeps a recent disk-miss/edit tombstone after a newer deposit
                // commits. Its readers temporarily hide that row until the native
                // guard expires; a committed column must not fail this checkpoint.
                long retryAt = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(2));
                boolean complete;
                do {
                    check(active);
                    complete = true;
                    for (var column : batch) if (!present(store, column)) { complete = false; break; }
                    if (complete) break;
                    Thread.sleep(50);
                } while (System.nanoTime() < retryAt);
                if (complete) break;
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Native VSS cache did not retain generated columns");
            }
        }
    }
    /** Queue-empty alone is insufficient: the last taken deposit may still be uncommitted. */
    public static void flush(Object store, BooleanSupplier active) throws Exception {
        check(active);
        if (!(boolean) invoke(store, "awaitDepositQueueEmpty", new Class<?>[]{long.class}, 30_000L))
            throw new IllegalStateException("Native VSS cache deposit queue did not drain");
        for (int attempt = 0; attempt < 5; attempt++) {
            check(active);
            Object policy = invoke(store, "adoptedPolicy", new Class<?>[0]);
            long cap = (long) invoke(policy, "maxDbBytes", new Class<?>[0]);
            int resweep = (int) invoke(policy, "resweepSeconds", new Class<?>[0]);
            long revision = (long) invoke(policy, "revision", new Class<?>[0]);
            var receipt = (CompletableFuture<?>) invoke(store, "updatePolicy", new Class<?>[]{long.class, int.class, long.class}, cap, resweep, revision);
            try { receipt.get(30, TimeUnit.SECONDS); return; }
            catch (java.util.concurrent.ExecutionException error) {
                // A concurrent native reload may supersede this unchanged policy.
                // Its owner still commits first; retry the current adopted revision.
                if (!(error.getCause() instanceof CancellationException)) throw error;
            } catch (CancellationException superseded) { check(active); }
        }
        throw new IllegalStateException("Native VSS policy kept changing while committing generated columns");
    }
    private static boolean present(Object store, Column column) throws Exception {
        Object hit = invoke(store, "get", new Class<?>[]{String.class, long.class}, column.dimension(), column.position());
        if (hit == null) return false;
        long stamp = (long) invoke(hit, "columnTimestamp", new Class<?>[0]);
        if (stamp > column.stamp()) return true;
        byte[] bytes = (byte[]) invoke(hit, "sectionBytes", new Class<?>[0]);
        return stamp == column.stamp() && Arrays.equals(normalize(bytes), normalize(column.bytes()));
    }
    private static byte[] normalize(byte[] bytes) { return bytes == null ? new byte[0] : bytes; }
    private static void check(BooleanSupplier active) { if (!active.getAsBoolean()) throw new CancellationException("VSS generation is closing"); }
    static Object invoke(Object target, String method, Class<?>[] types, Object... arguments) throws Exception {
        try { return target.getClass().getMethod(method, types).invoke(target, arguments); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception exception) throw exception;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw error;
        }
    }
}
