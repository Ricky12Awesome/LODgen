package dev.ricky12awesome.lodgen;

import dev.ricky12awesome.lodgen.generation.GenerationBudget;
import dev.ricky12awesome.lodgen.generation.CpuThrottle;
import dev.ricky12awesome.lodgen.generation.GenerationLag;
import dev.ricky12awesome.lodgen.generation.GenerationPolicy;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;

/** LODgen owns generation threads; DH still controls its generation plan and mode. */
public final class GenerationSettings {
    private static final GenerationLag LAG = new GenerationLag();
    private static DhThreadSettings dhThreads;
    // All dimensions and renderer pipelines spend the same process-wide budget.
    public static final CpuThrottle CPU_THROTTLE = new CpuThrottle(Runtime.getRuntime().availableProcessors(),
            () -> {
                var budget = current();
                return budget.threads() * budget.runRatio() / Math.max(1, Runtime.getRuntime().availableProcessors());
            }, () -> current().batches());

    public static void initialize() {
        if (ModSupport.loaded("distanthorizons")) {
            dhThreads = new DhThreadSettings();
            dhThreads.register();
        }
        LodgenConfig.listen(settings -> CPU_THROTTLE.refresh());
    }

    public static GenerationPolicy policy() {
        return ModSupport.loaded("distanthorizons") ? DhSettings.policy()
                : new GenerationPolicy(LodgenConfig.INSTANCE.enabled(), GenerationPolicy.Plan.NO_DH, false);
    }
    public static GenerationBudget current() {
        return budget(LodgenConfig.INSTANCE);
    }
    static GenerationBudget budget(LodgenConfig settings) {
        int processors = Runtime.getRuntime().availableProcessors();
        long heap = Runtime.getRuntime().maxMemory();
        return GenerationBudget.of(settings.threads(), settings.preventLag() ? LAG.ratio() : 1, processors, heap);
    }
    public static void serverTick(Object server, long elapsedNanos) {
        LAG.record(server, elapsedNanos);
        refresh();
    }
    public static void serverStopped(Object server) {
        LAG.remove(server);
        refresh();
    }
    private static void refresh() {
        if (dhThreads != null) dhThreads.accept(LodgenConfig.INSTANCE);
        CPU_THROTTLE.refresh();
    }
    private static final class DhSettings {
        static GenerationPolicy policy() {
            return new GenerationPolicy(LodgenConfig.INSTANCE.enabled(),
                    GenerationPolicy.Plan.valueOf(Config.Common.WorldGenerator.generatorPlan.get().name()),
                    Config.Common.WorldGenerator.chunkGeneratorMode.get() == EDhApiDistantGeneratorMode.FEATURES);
        }
    }
    private GenerationSettings() {}
}
