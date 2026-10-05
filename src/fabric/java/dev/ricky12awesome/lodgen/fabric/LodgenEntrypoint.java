package dev.ricky12awesome.lodgen.fabric;

import dev.ricky12awesome.lodgen.LodgenConfig;
import net.fabricmc.api.ModInitializer;

public final class LodgenEntrypoint implements ModInitializer {
    @Override public void onInitialize() { LodgenConfig.LOGGER.info("LODgen loaded (development version 0.0.0)"); }
}
