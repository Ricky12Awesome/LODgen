package dev.lodgen;

import dev.lodgen.generation.GenerationBudget;

/** Keep DH classes out of Voxy-only installations. Query live DH settings as
 * well as our immutable configuration; either renderer can change CPU load.
 */
public final class GenerationSettings {
    public static dev.lodgen.generation.GenerationPolicy policy() {
        return ModSupport.loaded("distanthorizons") ? DhSettings.policy()
                : new dev.lodgen.generation.GenerationPolicy(LodgenConfig.INSTANCE.enabled(), dev.lodgen.generation.GenerationPolicy.Plan.NO_DH, false);
    }
    public static GenerationBudget current() {
        int processors = Runtime.getRuntime().availableProcessors();
        long heap = Runtime.getRuntime().maxMemory();
        return ModSupport.loaded("distanthorizons") ? DhSettings.budget(processors, heap)
                : GenerationBudget.voxy(LodgenConfig.INSTANCE.cpuLoad(), processors, heap);
    }
    private static final class DhSettings {
        static dev.lodgen.generation.GenerationPolicy policy() {
            return new dev.lodgen.generation.GenerationPolicy(LodgenConfig.INSTANCE.enabled(),
                    dev.lodgen.generation.GenerationPolicy.Plan.valueOf(com.seibel.distanthorizons.core.config.Config.Common.WorldGenerator.generatorPlan.get().name()),
                    com.seibel.distanthorizons.core.config.Config.Common.WorldGenerator.chunkGeneratorMode.get()
                            == com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode.FEATURES);
        }
        static GenerationBudget budget(int processors, long heap) {
            return GenerationBudget.of(com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.numberOfThreads.get(),
                    com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.threadRunTimeRatio.get(), processors, heap);
        }
    }
    private GenerationSettings() {}
}
