package dev.ricky12awesome.lodgen.generation;

/** Processor budgets shared by native admission and renderer conversion. */
public record GenerationBudget(int threads, double runRatio, int batches) {
    public static GenerationBudget forCpuLoad(int load, int processors, long heapBytes) {
        processors = Math.max(1, processors);
        double fraction = cpuFraction(load);
        int threads = load == 1 ? 1 : Math.max(1, (int) Math.ceil(processors * fraction));
        // Compensate for rounding on CPUs whose thread count isn't divisible by four.
        double ratio = Math.min(1, processors * fraction / threads);
        var budget = of(threads, ratio, processors, heapBytes);
        // Only Maximum keeps a large dependency window. Even a single native
        // batch can fan out onto all of Minecraft/C2ME's generation workers.
        return new GenerationBudget(threads, budget.runRatio(), load == 5 ? budget.batches() : Math.min(threads, budget.batches()));
    }

    public static double cpuFraction(int load) {
        return switch (load) {
            case 1 -> 0.01;
            case 2 -> 0.25;
            case 3 -> 0.50;
            case 4 -> 0.75;
            case 5 -> 1.00;
            default -> throw new IllegalArgumentException("CPU load must be 1–5");
        };
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
