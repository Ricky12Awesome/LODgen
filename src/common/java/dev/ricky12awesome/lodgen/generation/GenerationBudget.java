package dev.ricky12awesome.lodgen.generation;

/** Processor budgets shared by native admission and renderer conversion. */
public record GenerationBudget(int threads, double runRatio, int batches) {
    public static GenerationBudget forThreads(int threads, int processors, long heapBytes) {
        return of(threads, 1, processors, heapBytes);
    }

    public static GenerationBudget of(int threads, double runRatio, int processors, long heapBytes) {
        threads = Math.max(1, Math.min(Math.max(1, processors), threads));
        runRatio = Math.max(0.01, Math.min(1, runRatio));
        double fraction = threads / (double) Math.max(1, processors);
        int ahead = fraction <= 0.125 ? 1 : fraction <= 0.5 ? 2 : fraction <= 0.75 ? 4 : 8;
        long memoryLimit = Math.max(1, heapBytes / (128L * 1024 * 1024));
        int batches = (int) Math.max(1, Math.ceil(Math.min(memoryLimit, (long) threads * ahead) * runRatio));
        return new GenerationBudget(threads, runRatio, batches);
    }
}
