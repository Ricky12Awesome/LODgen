package dev.lodgen.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(ChunkMap.class)
public interface ChunkMapAccess {
    @Invoker("getUpdatingChunkIfPresent") ChunkHolder lodgen$holder(long pos);
}
