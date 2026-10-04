package dev.lodgen.mixin;

import dev.lodgen.generation.SavePolicy;
import dev.lodgen.minecraft.ChunkPersistence;
import dev.lodgen.minecraft.PersistenceRegistry;
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
    @Unique private SavePolicy lodgen$policy;
    @Unique private SavePolicy lodgen$policy() {
        if (lodgen$policy == null) lodgen$policy = PersistenceRegistry.get(storageInfo());
        return lodgen$policy;
    }

    @Inject(method = "store(Lnet/minecraft/world/level/ChunkPos;Lnet/minecraft/nbt/CompoundTag;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void lodgen$skipWrite(ChunkPos pos, CompoundTag data, CallbackInfoReturnable<CompletableFuture<Void>> ci) {
        if (ChunkPersistence.suppress(lodgen$policy(), pos)) ci.setReturnValue(CompletableFuture.completedFuture(null));
    }

    // #if MC_26
    @Inject(method = "store(Lnet/minecraft/world/level/ChunkPos;Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"), cancellable = true)
    private void lodgen$skipLazyWrite(ChunkPos pos, java.util.function.Supplier<CompoundTag> data, CallbackInfoReturnable<CompletableFuture<Void>> ci) {
        if (ChunkPersistence.suppress(lodgen$policy(), pos)) ci.setReturnValue(CompletableFuture.completedFuture(null));
    }
    // #endif

}
