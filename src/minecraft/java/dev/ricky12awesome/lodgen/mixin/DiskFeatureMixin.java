package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.minecraft.DiskPredicateAccess;
import dev.ricky12awesome.lodgen.minecraft.DiskBlockReads;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import net.minecraft.world.level.levelgen.feature.DiskFeature;
// #if MC_263
import net.minecraft.world.level.chunk.ChunkGenerator;
// #else
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
// #endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
// #if MC_1211_FABRIC
import org.spongepowered.asm.mixin.injection.Desc;
// #endif

@Mixin(DiskFeature.class)
public abstract class DiskFeatureMixin {
    @WrapOperation(
            // #if MC_263
            method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
            // #else
            method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            // #endif
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"))
    // #if MC_263
    private Iterable<BlockPos> lodgen$columns(BlockPos from, BlockPos to, Operation<Iterable<BlockPos>> original, WorldGenLevel level,
            ChunkGenerator generator, RandomSource random, BlockPos origin) {
        var config = (DiskFeature) (Object) this;
    // #else
    private Iterable<BlockPos> lodgen$columns(BlockPos from, BlockPos to, Operation<Iterable<BlockPos>> original, FeaturePlaceContext<DiskConfiguration> context) {
        var config = context.config();
        var level = context.level();
    // #endif
        var plan = ((DiskPredicateAccess) (Object) config).lodgen$targetPlan();
        return plan != null && plan.rejectsDisk(level, from, to, config.halfHeight())
                ? java.util.List.of() : BlockPos.betweenClosed(from, to);
    }

    // #if MC_263
    @WrapMethod(method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z")
    private boolean lodgen$scan(WorldGenLevel level, ChunkGenerator generator, RandomSource random, BlockPos origin,
                                 Operation<Boolean> original) {
        var config = (DiskFeature) (Object) this;
        boolean eligible = ((DiskPredicateAccess) (Object) this).lodgen$targetPlan() != null
                && config.stateProvider().value().getClass().getName().startsWith("net.minecraft.");
        try (var scope = DiskBlockReads.open(level, eligible)) { return original.call(level, generator, random, origin); }
    }
    // #else
    @WrapMethod(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z")
    private boolean lodgen$scan(FeaturePlaceContext<DiskConfiguration> context, Operation<Boolean> original) {
        var config = context.config();
        boolean eligible = ((DiskPredicateAccess) (Object) config).lodgen$targetPlan() != null
                && config.stateProvider().getClass().getName().startsWith("net.minecraft.");
        try (var scope = DiskBlockReads.open(context.level(), eligible)) { return original.call(context); }
    }
    // #endif

    @WrapOperation(method = "placeColumn", at = @At(value = "INVOKE",
            // #if MC_1211_FABRIC
            desc = @Desc(owner = BlockPredicate.class, value = "test",
                    args = {Object.class, Object.class}, ret = boolean.class)))
            // #else
            target = "Lnet/minecraft/world/level/levelgen/blockpredicates/BlockPredicate;test(Ljava/lang/Object;Ljava/lang/Object;)Z"))
            // #endif
    // #if MC_263
    private boolean lodgen$testTarget(BlockPredicate target, Object region, Object position, Operation<Boolean> original,
                                      WorldGenLevel level, RandomSource random,
                                      int maxY, int minY, BlockPos.MutableBlockPos column) {
        var plan = ((DiskPredicateAccess) (Object) this).lodgen$targetPlan();
    // #else
    private boolean lodgen$testTarget(BlockPredicate target, Object region, Object position, Operation<Boolean> original,
                                      DiskConfiguration config, WorldGenLevel level, RandomSource random,
                                      int maxY, int minY, BlockPos.MutableBlockPos column) {
        var plan = ((DiskPredicateAccess) (Object) config).lodgen$targetPlan();
    // #endif
        return plan == null ? target.test((WorldGenLevel) region, (BlockPos) position)
                : plan.test((WorldGenLevel) region, (BlockPos) position);
    }

}
