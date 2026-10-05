package dev.ricky12awesome.lodgen.generation;

/** Distinguishes synchronous worldgen lookups from a player's/pregen's request.
 * Owner identity isolates dimensions. Restore even when a feature throws, since
 * C2ME reuses worker threads for unrelated normal generation.
 */
public final class GenerationScope implements AutoCloseable {
    private static final ThreadLocal<Object> CURRENT = new ThreadLocal<>();
    private final Object previous;

    public GenerationScope(Object owner, boolean transientGeneration) {
        previous = CURRENT.get();
        if (transientGeneration) CURRENT.set(owner);
        else CURRENT.remove();
    }

    public static boolean isTransient(Object owner) { return owner != null && CURRENT.get() == owner; }

    @Override public void close() {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }
}
