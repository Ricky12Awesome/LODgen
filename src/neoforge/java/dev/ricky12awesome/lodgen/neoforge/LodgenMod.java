package dev.ricky12awesome.lodgen.neoforge;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.GenerationSettings;
import net.neoforged.fml.common.Mod;

@Mod("lodgen")
public final class LodgenMod {
    public LodgenMod() {
        GenerationSettings.initialize();
        LodgenConfig.LOGGER.info("LODgen loaded (development version 0.0.0)");
    }
}
