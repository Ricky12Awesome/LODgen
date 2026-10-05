package dev.ricky12awesome.lodgen.fabric;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.GenerationSettings;
import net.fabricmc.api.ModInitializer;

public final class LodgenEntrypoint implements ModInitializer {
    @Override public void onInitialize() {
        GenerationSettings.initialize();
        LodgenConfig.LOGGER.info("LODgen loaded (development version 0.0.0)");
    }
}
