package dev.lodgen.generation;

/** Immutable progress shared by command status and the vanilla action bar. */
public record GenerationProgress(int radius, long remainingChunks, State state) {
    public enum State { RUNNING, PAUSED, STOPPED, COMPLETE, WAITING_FOR_RENDERER, DISABLED }

    public long estimatedSeconds(double chunksPerSecond) {
        if (state == State.COMPLETE) return 0;
        if (state != State.RUNNING || remainingChunks < 0 || !Double.isFinite(chunksPerSecond) || chunksPerSecond <= 0) return -1;
        return (long) Math.ceil(remainingChunks / chunksPerSecond);
    }

    public static String duration(long seconds) {
        if (seconds < 0) return "—";
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return seconds / 60 + "m " + seconds % 60 + "s";
        if (seconds < 86400) return seconds / 3600 + "h " + seconds / 60 % 60 + "m";
        return seconds / 86400 + "d " + seconds / 3600 % 24 + "h";
    }
}
