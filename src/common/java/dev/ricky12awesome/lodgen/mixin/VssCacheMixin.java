package dev.ricky12awesome.lodgen.mixin;

import dev.ricky12awesome.lodgen.generation.VssCachePolicy;
import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.voxy.VssNativeStore;
import java.io.IOException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.concurrent.atomic.AtomicBoolean;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Retains native SQLite LOD rows when their source chunks have no disk save. */
@Pseudo
@Mixin(targets = "dev.vox.lss.common.store.SqliteLodStore", remap = false)
public abstract class VssCacheMixin {
    @Shadow @Final private AtomicBoolean shutdown;

    @Inject(method = "shutdown", at = @At("HEAD"), require = 1)
    private void lodgen$commitBeforeShutdown(CallbackInfo callback) {
        if (shutdown.get()) return;
        try {
            // RequestService has joined its producers before it closes the store.
            // Commit queued native deposits before shutdown interrupts the batcher.
            VssNativeStore.flush(this, () -> !shutdown.get());
        } catch (Throwable failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            LodgenConfig.LOGGER.error("Cannot commit pending native VSS LODs before store shutdown", failure);
        }
    }

    @Redirect(method = "runSweep", at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;isDirectory(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"), require = 1)
    private boolean lodgen$knownDirectory(Path path, LinkOption[] options) {
        return VssCachePolicy.knownDirectory(path, options);
    }

    @Redirect(method = "sweepDimension", at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;exists(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z"), require = 1)
    private boolean lodgen$regionCandidate(Path path, LinkOption[] options) {
        return VssCachePolicy.regionCandidate(path, options);
    }

    @Redirect(method = "sweepDimension", at = @At(value = "INVOKE", target = "Ljava/nio/file/Files;getLastModifiedTime(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Ljava/nio/file/attribute/FileTime;"), require = 2)
    private FileTime lodgen$regionMtime(Path path, LinkOption[] options) throws IOException {
        return VssCachePolicy.regionMtime(path, options);
    }

    @Inject(method = "readHeaderTimestamps", at = @At("HEAD"), cancellable = true, require = 1)
    private void lodgen$unsavedHeader(Path path, CallbackInfoReturnable<int[]> result) {
        result.setReturnValue(VssCachePolicy.readHeaderTimestamps(path));
    }
}
