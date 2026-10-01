package dev.dhc2me.mixin;

import dev.dhc2me.generation.SavePolicy;
import dev.dhc2me.minecraft.PersistenceRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.IOWorker;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(IOWorker.class)
public abstract class WorkerMixin {
    @Shadow public abstract RegionStorageInfo storageInfo();
    @Unique private SavePolicy dhc2me$policy;
    @Unique private SavePolicy dhc2me$policy() {
        if (dhc2me$policy == null) dhc2me$policy = PersistenceRegistry.get(storageInfo());
        return dhc2me$policy;
    }

    @Inject(method = "store(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void dhc2me$skipWrite(ChunkPos pos, CompoundTag data, CallbackInfoReturnable<CompletableFuture<Void>> ci) {
        if (PersistenceRegistry.suppress(dhc2me$policy(), pos)) ci.setReturnValue(CompletableFuture.completedFuture(null));
    }

    // #if MC_26
    @Inject(method = "store(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void dhc2me$skipLazyWrite(ChunkPos pos, java.util.function.Supplier<CompoundTag> data, CallbackInfoReturnable<CompletableFuture<Void>> ci) {
        if (PersistenceRegistry.suppress(dhc2me$policy(), pos)) ci.setReturnValue(CompletableFuture.completedFuture(null));
    }
    // #endif

}
