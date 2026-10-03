package dev.lodgen.mixin;

import dev.lodgen.minecraft.DiskPredicateAccess;
import dev.lodgen.minecraft.DiskBlockReads;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
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
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DiskFeature.class)
public abstract class DiskFeatureMixin {
    // #if MC_1211_FABRIC
    // Legacy Fabric runs in the intermediary namespace. Keep explicit selector
    // descriptors and the nested invocation in that same namespace.
    @Redirect(method = "method_13151(Lnet/minecraft/class_5821;)Z", remap = false,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_2338;method_10097(Lnet/minecraft/class_2338;Lnet/minecraft/class_2338;)Ljava/lang/Iterable;", remap = false))
    // #else
    @Redirect(
            // #if MC_263
            method = "place(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/chunk/ChunkGenerator;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/BlockPos;)Z",
            // #else
            method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z",
            // #endif
            at = @At(value = "INVOKE", target = "Lnet/minecraft/core/BlockPos;betweenClosed(Lnet/minecraft/core/BlockPos;Lnet/minecraft/core/BlockPos;)Ljava/lang/Iterable;"))
    // #endif
    // #if MC_263
    private Iterable<BlockPos> lodgen$columns(BlockPos from, BlockPos to, WorldGenLevel level,
            ChunkGenerator generator, RandomSource random, BlockPos origin) {
        var config = (DiskFeature) (Object) this;
    // #else
    private Iterable<BlockPos> lodgen$columns(BlockPos from, BlockPos to, FeaturePlaceContext<DiskConfiguration> context) {
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
    // #if MC_1211_FABRIC
    @WrapMethod(method = "method_13151(Lnet/minecraft/class_5821;)Z", remap = false)
    // #else
    @WrapMethod(method = "place(Lnet/minecraft/world/level/levelgen/feature/FeaturePlaceContext;)Z")
    // #endif
    private boolean lodgen$scan(FeaturePlaceContext<DiskConfiguration> context, Operation<Boolean> original) {
        var config = context.config();
        boolean eligible = ((DiskPredicateAccess) (Object) config).lodgen$targetPlan() != null
                && config.stateProvider().getClass().getName().startsWith("net.minecraft.");
        try (var scope = DiskBlockReads.open(context.level(), eligible)) { return original.call(context); }
    }
    // #endif

    // #if MC_1211_FABRIC
    @Redirect(method = "method_43160", remap = false, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/class_6646;test(Ljava/lang/Object;Ljava/lang/Object;)Z", remap = false))
    // #else
    @Redirect(method = "placeColumn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/blockpredicates/BlockPredicate;test(Ljava/lang/Object;Ljava/lang/Object;)Z"))
    // #endif
    // #if MC_263
    private boolean lodgen$testTarget(BlockPredicate target, Object region, Object position,
                                      WorldGenLevel level, RandomSource random,
                                      int maxY, int minY, BlockPos.MutableBlockPos column) {
        var plan = ((DiskPredicateAccess) (Object) this).lodgen$targetPlan();
    // #else
    private boolean lodgen$testTarget(BlockPredicate target, Object region, Object position,
                                      DiskConfiguration config, WorldGenLevel level, RandomSource random,
                                      int maxY, int minY, BlockPos.MutableBlockPos column) {
        var plan = ((DiskPredicateAccess) (Object) config).lodgen$targetPlan();
    // #endif
        return plan == null ? target.test((WorldGenLevel) region, (BlockPos) position)
                : plan.test((WorldGenLevel) region, (BlockPos) position);
    }

}
