package dev.lodgen.mixin;

import dev.lodgen.generation.SavePolicy;
import dev.lodgen.minecraft.ChunkPersistence;
import dev.lodgen.minecraft.PersistenceRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.function.LongFunction;

/** Optional storage guard only. Generation always uses vanilla chunk requests.
 * C2ME's raw serializer bypasses IOWorker.store, so cover its write admission too.
 */
@Pseudo
@Mixin(targets = "com.ishland.c2me.rewrites.chunkio.common.C2MEStorageThread", remap = false)
public abstract class C2meStorageMixin {
    @Unique private RegionStorageInfo lodgen$info;
    @Unique private Path lodgen$folder;
    @Unique private SavePolicy lodgen$policy;
    @Unique private SavePolicy lodgen$policy() {
        if (lodgen$policy == null) lodgen$policy = PersistenceRegistry.get(lodgen$info);
        return lodgen$policy;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lodgen$bind(RegionStorageInfo info, Path path, boolean sync, LongFunction<Executor> executor, CallbackInfo ci) {
        lodgen$info = info;
        lodgen$folder = path;
    }

    // Runs after the pending-write cache check. Blending scans whole regions,
    // including positions outside our generation dependency footprint.
    @Inject(method = "scheduleChunkRead", at = @At("HEAD"), cancellable = true)
    private void lodgen$avoidCreatingRegion(long pos, CompletableFuture<CompoundTag> result, StreamTagVisitor scanner, CallbackInfo ci) {
        SavePolicy policy = lodgen$policy();
        int x = (int) pos, z = (int) (pos >> 32);
        if (policy != null && !ChunkPersistence.regionExists(lodgen$folder, x, z)) {
            result.complete(null);
            ci.cancel();
        }
    }

    @Inject(method = "write0", at = @At("HEAD"), cancellable = true)
    private void lodgen$skipWrite(long pos, CompletionStage<?> data, CallbackInfo ci) {
        SavePolicy policy = lodgen$policy();
        if (ChunkPersistence.suppress(policy, (int) pos, (int) (pos >> 32))) ci.cancel();
    }

}
