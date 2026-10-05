package dev.ricky12awesome.lodgen.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.ricky12awesome.lodgen.minecraft.OreBlockAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.BulkSectionAccess;
import net.minecraft.world.level.levelgen.feature.OreFeature;
// #if MC_263
import net.minecraft.world.level.levelgen.feature.BlockReplacement;
// #else
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
// #endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Iterator;
import java.util.List;
import java.util.function.Function;

/** Retain vanilla's geometry, rule order, random draws and writes. */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
    // #if MC_1211_FABRIC
    @Redirect(method = "method_13629", remap = false, at = @At(value = "NEW", target = "net/minecraft/class_5867", remap = false))
    // #else
    @Redirect(method = "doPlace", at = @At(value = "NEW", target = "net/minecraft/world/level/chunk/BulkSectionAccess"))
    // #endif
    private BulkSectionAccess lodgen$oreAccess(LevelAccessor level) {
        return level instanceof WorldGenRegion && ((Object) this).getClass() == OreFeature.class
                ? new OreBlockAccess(level) : new BulkSectionAccess(level);
    }

    // #if MC_1211_FABRIC
    @Redirect(method = "method_13629", remap = false, at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;", remap = false))
    // #else
    @Redirect(method = "doPlace", at = @At(value = "INVOKE", target = "Ljava/util/List;iterator()Ljava/util/Iterator;"))
    // #endif
    private Iterator<?> lodgen$oreTargets(List<?> targets, @Local BulkSectionAccess access) {
        return access instanceof OreBlockAccess ore ? ore.targets(targets) : targets.iterator();
    }

    // #if MC_263
    @Redirect(method = "doPlace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/OreFeature;canPlaceOre(Lnet/minecraft/world/level/block/state/BlockState;Ljava/util/function/Function;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/feature/BlockReplacement;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Z"))
    private boolean lodgen$oreTarget(OreFeature feature, BlockState state, Function<BlockPos, BlockState> originalReader,
            RandomSource random, BlockReplacement target, BlockPos.MutableBlockPos position, @Local BulkSectionAccess access) {
        return feature.canPlaceOre(state, access instanceof OreBlockAccess ore ? ore : originalReader, random, target, position);
    }
    // #else
    // #if MC_1211_FABRIC
    @Redirect(method = "method_13629", remap = false, at = @At(value = "INVOKE", target = "Lnet/minecraft/class_3122;method_33983(Lnet/minecraft/class_2680;Ljava/util/function/Function;Lnet/minecraft/class_5819;Lnet/minecraft/class_3124;Lnet/minecraft/class_3124$class_5876;Lnet/minecraft/class_2338$class_2339;)Z", remap = false))
    // #else
    @Redirect(method = "doPlace", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/feature/OreFeature;canPlaceOre(Lnet/minecraft/world/level/block/state/BlockState;Ljava/util/function/Function;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/feature/configurations/OreConfiguration;Lnet/minecraft/world/level/levelgen/feature/configurations/OreConfiguration$TargetBlockState;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Z"))
    // #endif
    private boolean lodgen$oreTarget(BlockState state, Function<BlockPos, BlockState> originalReader, RandomSource random,
            OreConfiguration config, OreConfiguration.TargetBlockState target, BlockPos.MutableBlockPos position,
            @Local BulkSectionAccess access) {
        // The discarded bound method reference can now be eliminated by the JIT.
        return OreFeature.canPlaceOre(state, access instanceof OreBlockAccess ore ? ore : originalReader,
                random, config, target, position);
    }
    // #endif
}
