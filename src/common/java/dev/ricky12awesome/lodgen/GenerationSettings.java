package dev.ricky12awesome.lodgen;

import dev.ricky12awesome.lodgen.generation.GenerationBudget;
import dev.ricky12awesome.lodgen.generation.GenerationPolicy;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;

/** Keep DH classes out of Voxy-only installations. Query live DH settings as
 * well as our immutable configuration; either renderer can change CPU load.
 */
public final class GenerationSettings {
    public static GenerationPolicy policy() {
        return ModSupport.loaded("distanthorizons") ? DhSettings.policy()
                : new GenerationPolicy(LodgenConfig.INSTANCE.enabled(), GenerationPolicy.Plan.NO_DH, false);
    }
    public static GenerationBudget current() {
        int processors = Runtime.getRuntime().availableProcessors();
        long heap = Runtime.getRuntime().maxMemory();
        return ModSupport.loaded("distanthorizons") ? DhSettings.budget(processors, heap)
                : GenerationBudget.voxy(LodgenConfig.INSTANCE.cpuLoad(), processors, heap);
    }
    private static final class DhSettings {
        static GenerationPolicy policy() {
            return new GenerationPolicy(LodgenConfig.INSTANCE.enabled(),
                    GenerationPolicy.Plan.valueOf(Config.Common.WorldGenerator.generatorPlan.get().name()),
                    Config.Common.WorldGenerator.chunkGeneratorMode.get() == EDhApiDistantGeneratorMode.FEATURES);
        }
        static GenerationBudget budget(int processors, long heap) {
            return GenerationBudget.of(Config.Common.MultiThreading.numberOfThreads.get(),
                    Config.Common.MultiThreading.threadRunTimeRatio.get(), processors, heap);
        }
    }
    private GenerationSettings() {}
}
