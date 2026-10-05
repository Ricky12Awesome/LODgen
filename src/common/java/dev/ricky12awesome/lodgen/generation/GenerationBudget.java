package dev.ricky12awesome.lodgen.generation;

/** DH's processor fractions, with a larger asynchronous native-generation window.
 * The heap bound leaves room for dependency chunks, renderer data and normal play.
 */
public record GenerationBudget(int threads, double runRatio, int batches) {
    public static GenerationBudget forCpuLoad(int load, int processors, long heapBytes) {
        double fraction = switch (load) {
            case 1 -> 0.10;
            case 2 -> 0.25;
            case 3 -> 0.50;
            case 4 -> 0.75;
            case 5 -> 1.00;
            default -> throw new IllegalArgumentException("CPU load must be 1–5");
        };
        return of(Math.max(1, (int) Math.ceil(processors * fraction)), load == 1 ? 0.5 : 1.0, processors, heapBytes);
    }

    public static GenerationBudget of(int threads, double runRatio, int processors, long heapBytes) {
        threads = Math.max(1, threads);
        double fraction = threads / (double) Math.max(1, processors);
        int ahead = fraction <= 0.125 ? 1 : fraction <= 0.5 ? 2 : fraction <= 0.75 ? 4 : 8;
        long memoryLimit = Math.max(1, heapBytes / (128L * 1024 * 1024));
        int batches = (int) Math.max(1, Math.min(memoryLimit, (long) threads * ahead));
        return new GenerationBudget(threads, Math.max(0.01, Math.min(1, runRatio)), batches);
    }
}
