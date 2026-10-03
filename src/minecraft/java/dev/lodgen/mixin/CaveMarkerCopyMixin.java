package dev.lodgen.mixin;

import dev.lodgen.minecraft.CaveSurface;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelChunk.class)
public abstract class CaveMarkerCopyMixin {
    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("TAIL"))
    private void lodgen$copyMarkers(ServerLevel level, ProtoChunk source, LevelChunk.PostLoadProcessor processor, CallbackInfo callback) {
        var data = (CaveSurface) (Object) this;
        data.lodgen$surfaceFloors(((CaveSurface) source).lodgen$surfaceFloors());
        data.lodgen$markers(((CaveSurface) source).lodgen$markers());
    }
}
