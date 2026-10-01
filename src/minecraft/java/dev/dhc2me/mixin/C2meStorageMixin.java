package dev.dhc2me.mixin;

import dev.dhc2me.generation.SavePolicy;
import dev.dhc2me.minecraft.PersistenceRegistry;
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
import java.nio.file.Files;
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
    @Unique private RegionStorageInfo dhc2me$info;
    @Unique private Path dhc2me$folder;
    @Unique private SavePolicy dhc2me$policy;
    @Unique private SavePolicy dhc2me$policy() {
        if (dhc2me$policy == null) dhc2me$policy = PersistenceRegistry.get(dhc2me$info);
        return dhc2me$policy;
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dhc2me$bind(RegionStorageInfo info, Path path, boolean sync, LongFunction<Executor> executor, CallbackInfo ci) {
        dhc2me$info = info;
        dhc2me$folder = path;
    }

    // Runs after the pending-write cache check. Blending scans whole regions,
    // including positions outside our generation dependency footprint.
    @Inject(method = "scheduleChunkRead", at = @At("HEAD"), cancellable = true)
    private void dhc2me$avoidCreatingRegion(long pos, CompletableFuture<CompoundTag> result, StreamTagVisitor scanner, CallbackInfo ci) {
        SavePolicy policy = dhc2me$policy();
        int x = (int) pos, z = (int) (pos >> 32);
        if (policy != null && !Files.exists(dhc2me$folder.resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca"))) {
            result.complete(null);
            ci.cancel();
        }
    }

    @Inject(method = "write0", at = @At("HEAD"), cancellable = true)
    private void dhc2me$skipWrite(long pos, CompletionStage<?> data, CallbackInfo ci) {
        SavePolicy policy = dhc2me$policy();
        if (policy != null && policy.suppress((int) pos, (int) (pos >> 32))) ci.cancel();
    }

}
