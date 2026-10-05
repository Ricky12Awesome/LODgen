package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.minecraft.LodCaveGeneration;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
// #if MC_263
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
// #else
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
// #endif
import org.spongepowered.asm.mixin.injection.At;

// #if MC_263
@Mixin(FeaturePlacer.class)
// #else
@Mixin(ConfiguredFeature.class)
// #endif
public abstract class CaveFeatureMixin {
    // #if MC_263
    @WrapOperation(method = "place(Lnet/minecraft/world/level/levelgen/placement/PlacedFeature;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;Z)Z", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/Feature;place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z"))
    private boolean lodgen$surfaceFeature(Feature feature, WorldGenLevel level, ChunkGenerator generator, RandomSource random,
            BlockPos position, Operation<Boolean> original) {
        return !LodCaveGeneration.skipFeature(level, position, feature) && original.call(feature, level, generator, random, position);
    }
    // #else
    @Inject(method = "place", at = @At("HEAD"), cancellable = true)
    private void lodgen$surfaceFeature(WorldGenLevel level, ChunkGenerator generator, RandomSource random,
            BlockPos position, CallbackInfoReturnable<Boolean> callback) {
        if (LodCaveGeneration.skipFeature(level, position, ((ConfiguredFeature<?, ?>) (Object) this).feature())) callback.setReturnValue(false);
    }
    // #endif
}
