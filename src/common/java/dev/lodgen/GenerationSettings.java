package dev.lodgen;

import dev.lodgen.generation.GenerationBudget;

/** Keep DH classes out of Voxy-only installations. Query live DH settings as
 * well as our immutable configuration; either renderer can change CPU load.
 */
public final class GenerationSettings {
    public static GenerationBudget current() {
        int processors = Runtime.getRuntime().availableProcessors();
        long heap = Runtime.getRuntime().maxMemory();
        return ModSupport.loaded("distanthorizons") ? DhSettings.budget(processors, heap)
                : GenerationBudget.voxy(LodgenConfig.INSTANCE.cpuLoad(), processors, heap);
    }
    private static final class DhSettings {
        static GenerationBudget budget(int processors, long heap) {
            return GenerationBudget.of(com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.numberOfThreads.get(),
                    com.seibel.distanthorizons.core.config.Config.Common.MultiThreading.threadRunTimeRatio.get(), processors, heap);
        }
    }
    private GenerationSettings() {}
}
