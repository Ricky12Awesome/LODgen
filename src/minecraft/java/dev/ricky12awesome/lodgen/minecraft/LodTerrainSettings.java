package dev.ricky12awesome.lodgen.minecraft;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import dev.ricky12awesome.lodgen.generation.CaveDensity;
import dev.ricky12awesome.lodgen.generation.CaveMode;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
// #if MC_1211
import net.minecraft.resources.ResourceLocation;
// #else
import net.minecraft.resources.Identifier;
// #endif
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

/** Applies the conservative cave and density changes to disposable terrain settings. */
final class LodTerrainSettings {
    private LodTerrainSettings() {}

    static NoiseGeneratorSettings withoutCaves(ServerLevel level, NoiseGeneratorSettings settings, CaveMode mode) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var encoded = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings).getOrThrow().getAsJsonObject();
        var router = encoded.getAsJsonObject("noise_router");
        // 26.3 moved the density tree into a registered holder. Encode its value
        // so the same conservative transformation can inspect the formula.
        // #if MC_263
        if (router.get("final_density").isJsonPrimitive()) {
            var key = ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(router.get("final_density").getAsString()));
            router.add("final_density", net.minecraft.world.level.levelgen.densityfunction.DensityFunction.CODEC
                    .encodeStart(ops, level.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key).value()).getOrThrow());
        }
        // #endif
        CaveDensity.Result result = CaveDensity.transform(router.get("final_density"), mode == CaveMode.EMPTY);
        JsonElement density = result.density();
        if (!result.supported() || density.toString().contains("/caves/")) return null;
        if (mode == CaveMode.EMPTY && result.emptyMasks() == 0) return null;
        router.add("final_density", density);
        if (encoded.has("ore_veins_enabled")) encoded.addProperty("ore_veins_enabled", false);
        if (encoded.has("aquifers_enabled")) encoded.addProperty("aquifers_enabled", false);
        // #if MC_263
        encoded.remove("aquifers");
        // #endif
        if (mode == CaveMode.EMPTY) {
            encoded.add("default_fluid", net.minecraft.world.level.block.state.BlockState.CODEC
                    .encodeStart(ops, Blocks.AIR.defaultBlockState()).getOrThrow());
        }
        return NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
    }
}
