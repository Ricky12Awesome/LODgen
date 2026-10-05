package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerChunkCache.class)
public abstract class ChunkCacheMixin {
    @Shadow @Final private ServerLevel level;
    // Even cache hits are adoption: no ticket might be added in that case.
    @Inject(method = {"getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;", "getChunkFuture(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;"}, at = @At("HEAD"))
    private void lodgen$adopt(int x, int z, ChunkStatus status, boolean create, CallbackInfoReturnable<?> ci) {
        if (create) PersistenceRegistry.normalRequest(level, x, z);
    }
}
