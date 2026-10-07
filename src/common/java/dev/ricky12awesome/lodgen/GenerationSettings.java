package dev.ricky12awesome.lodgen;

import dev.ricky12awesome.lodgen.generation.GenerationBudget;
import dev.ricky12awesome.lodgen.generation.CpuThrottle;
import dev.ricky12awesome.lodgen.generation.GenerationPolicy;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;

/** LODgen owns CPU load; DH still controls its generation plan and mode. */
public final class GenerationSettings {
    // All dimensions and renderer pipelines spend the same process-wide budget.
    public static final CpuThrottle CPU_THROTTLE = new CpuThrottle(Runtime.getRuntime().availableProcessors(),
            () -> GenerationBudget.cpuFraction(LodgenConfig.INSTANCE.cpuLoad()),
            () -> LodgenConfig.INSTANCE.cpuLoad() == 5 ? Integer.MAX_VALUE : current().batches());

    public static void initialize() {
        if (ModSupport.loaded("distanthorizons")) new DhCpuSettings().register();
    }

    public static GenerationPolicy policy() {
        return ModSupport.loaded("distanthorizons") ? DhSettings.policy()
                : new GenerationPolicy(LodgenConfig.INSTANCE.enabled(), GenerationPolicy.Plan.NO_DH, false);
    }
    public static GenerationBudget current() {
        int processors = Runtime.getRuntime().availableProcessors();
        long heap = Runtime.getRuntime().maxMemory();
        return GenerationBudget.forCpuLoad(LodgenConfig.INSTANCE.cpuLoad(), processors, heap);
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
