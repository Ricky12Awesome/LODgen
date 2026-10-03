package dev.lodgen.mixin;

import dev.lodgen.minecraft.CaveSurface;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ChunkAccess.class)
public abstract class CaveSurfaceMixin implements CaveSurface {
    @Unique private volatile int[] lodgen$surfaceFloors;
    @Unique private volatile dev.lodgen.minecraft.CaveMarkers lodgen$markers;
    public int[] lodgen$surfaceFloors() { return lodgen$surfaceFloors; }
    public void lodgen$surfaceFloors(int[] floors) { lodgen$surfaceFloors = floors; }
    public dev.lodgen.minecraft.CaveMarkers lodgen$markers() { return lodgen$markers; }
    public void lodgen$markers(dev.lodgen.minecraft.CaveMarkers markers) { lodgen$markers = markers; }
}
