package dev.lodgen.mixin;

import dev.lodgen.minecraft.LodCaveGeneration;
import dev.lodgen.minecraft.LodGenerationWorld;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NoiseBasedChunkGenerator.class)
public abstract class CaveNoiseMixin {
    @Inject(method = "getBaseHeight", at = @At("RETURN"), cancellable = true)
    private void lodgen$surfaceHeight(int x, int z, net.minecraft.world.level.levelgen.Heightmap.Types type,
            net.minecraft.world.level.LevelHeightAccessor height, RandomState random, CallbackInfoReturnable<Integer> callback) {
        var generator = (NoiseBasedChunkGenerator) (Object) this;
        if (LodGenerationWorld.mode(generator) == dev.lodgen.generation.CaveMode.EMPTY
                && type != net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR
                && type != net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG) {
            // #if MC_1211
            int min = height.getMinBuildHeight();
            // #else
            int min = height.getMinY();
            // #endif
            callback.setReturnValue(Math.max(callback.getReturnValueI(), Math.min(generator.getSeaLevel(), min + height.getHeight())));
        }
    }

    @Inject(method = "getBaseColumn", at = @At("RETURN"))
    private void lodgen$surfaceColumn(int x, int z, net.minecraft.world.level.LevelHeightAccessor height,
            RandomState random, CallbackInfoReturnable<net.minecraft.world.level.NoiseColumn> callback) {
        var generator = (NoiseBasedChunkGenerator) (Object) this;
        var fluid = LodGenerationWorld.surfaceFluid(generator);
        if (fluid == null || LodGenerationWorld.mode(generator) != dev.lodgen.generation.CaveMode.EMPTY) return;
        var column = callback.getReturnValue();
        // #if MC_1211
        int min = height.getMinBuildHeight();
        // #else
        int min = height.getMinY();
        // #endif
        int sea = Math.min(generator.getSeaLevel(), Math.min(min + height.getHeight(), generator.getMinY() + generator.getGenDepth()));
        for (int y = sea - 1; y >= Math.max(min, generator.getMinY()) && column.getBlock(y).isAir(); y--)
            column.setBlock(y, fluid);
    }

    @Inject(method = "createFluidPicker", at = @At("HEAD"), cancellable = true)
    private static void lodgen$emptyFluid(net.minecraft.world.level.levelgen.NoiseGeneratorSettings settings,
            CallbackInfoReturnable<net.minecraft.world.level.levelgen.Aquifer.FluidPicker> callback) {
        if (LodGenerationWorld.empty(settings)) {
            var air = new net.minecraft.world.level.levelgen.Aquifer.FluidStatus(Integer.MAX_VALUE,
                    net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            callback.setReturnValue((x, y, z) -> air);
        }
    }
    // #if MC_263
    @Inject(method = "generateCarvers", at = @At("HEAD"), cancellable = true)
    // #else
    @Inject(method = "applyCarvers", at = @At("HEAD"), cancellable = true)
    // #endif
    private void lodgen$skipCarvers(CallbackInfo callback) {
        if (LodGenerationWorld.simplified((NoiseBasedChunkGenerator) (Object) this)) callback.cancel();
    }

    // #if MC_263
    @Inject(method = "buildSurface", at = @At("HEAD"))
    private void lodgen$water(ChunkAccess chunk, net.minecraft.world.level.levelgen.NoiseChunk noise, RandomState random,
            net.minecraft.world.level.biome.BiomeManager biomes, java.util.Set<net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> possible,
            net.minecraft.world.level.levelgen.material.rule.MaterialRule rule, CallbackInfo callback) {
    // #else
    @Inject(method = "buildSurface(Lnet/minecraft/server/level/WorldGenRegion;Lnet/minecraft/world/level/StructureManager;Lnet/minecraft/world/level/levelgen/RandomState;Lnet/minecraft/world/level/chunk/ChunkAccess;)V", at = @At("HEAD"))
    private void lodgen$water(net.minecraft.server.level.WorldGenRegion region, net.minecraft.world.level.StructureManager structures,
            RandomState random, ChunkAccess chunk, CallbackInfo callback) {
    // #endif
        LodCaveGeneration.surfaceWater((NoiseBasedChunkGenerator) (Object) this, chunk, random);
    }
}
