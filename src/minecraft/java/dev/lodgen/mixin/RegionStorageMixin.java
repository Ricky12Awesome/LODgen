package dev.lodgen.mixin;

import dev.lodgen.minecraft.PersistenceRegistry;
import dev.lodgen.minecraft.ChunkPersistence;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StreamTagVisitor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFileStorage;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.Path;

@Mixin(RegionFileStorage.class)
public abstract class RegionStorageMixin {
    @Shadow @Final private Path folder;
    @Shadow @Final private RegionStorageInfo info;

    // Vanilla creates empty .mca files even for reads. Do not create one merely
    // to discover that a DH-only chunk has never been saved.
    private boolean lodgen$missing(ChunkPos pos) {
        return PersistenceRegistry.get(info) != null
                && !ChunkPersistence.regionExists(folder, PersistenceRegistry.x(pos), PersistenceRegistry.z(pos));
    }

    @Inject(method = "read", at = @At("HEAD"), cancellable = true)
    private void lodgen$read(ChunkPos pos, CallbackInfoReturnable<CompoundTag> ci) {
        if (lodgen$missing(pos)) ci.setReturnValue(null);
    }
    @Inject(method = "scanChunk", at = @At("HEAD"), cancellable = true)
    private void lodgen$scan(ChunkPos pos, StreamTagVisitor visitor, CallbackInfo ci) {
        if (lodgen$missing(pos)) ci.cancel();
    }
}
