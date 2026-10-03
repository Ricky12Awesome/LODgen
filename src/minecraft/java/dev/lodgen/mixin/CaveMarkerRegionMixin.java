package dev.lodgen.mixin;

import dev.lodgen.minecraft.CaveMarkers;
import dev.lodgen.minecraft.LodGenerationWorld;
import dev.lodgen.generation.CaveMode;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
public abstract class CaveMarkerRegionMixin {
    private CaveMarkers lodgen$markers(BlockPos position) {
        var region = (WorldGenRegion) (Object) this;
        // #if MC_1211
        int min = region.getMinBuildHeight();
        // #else
        int min = region.getMinY();
        // #endif
        if (position.getY() < min || position.getY() >= min + CaveMarkers.HEIGHT
                || LodGenerationWorld.mode(region.getLevel()) != CaveMode.EMPTY) return null;
        return CaveMarkers.at(region.getChunk(position.getX() >> 4, position.getZ() >> 4), position.getY());
    }
    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void lodgen$readMarker(BlockPos position, CallbackInfoReturnable<BlockState> callback) {
        var markers = lodgen$markers(position);
        if (markers != null) callback.setReturnValue(markers.get(position));
    }
    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void lodgen$readMarkerFluid(BlockPos position, CallbackInfoReturnable<net.minecraft.world.level.material.FluidState> callback) {
        var markers = lodgen$markers(position);
        if (markers != null) callback.setReturnValue(markers.get(position).getFluidState());
    }
    @Inject(method = "setBlock", at = @At("HEAD"), cancellable = true)
    private void lodgen$writeMarker(BlockPos position, BlockState state, int flags, int recursion, CallbackInfoReturnable<Boolean> callback) {
        var markers = lodgen$markers(position);
        if (markers != null) {
            if (!((WorldGenRegion) (Object) this).ensureCanWrite(position)) callback.setReturnValue(false);
            else { markers.set(position, state); callback.setReturnValue(true); }
        }
    }
}
