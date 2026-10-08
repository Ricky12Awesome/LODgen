package dev.ricky12awesome.lodgen.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The plugin guards the raw reader's private-storage accessor call. */
@Pseudo
@Mixin(targets = "dev.vox.lss.networking.server.ChunkDiskReader", remap = false)
public abstract class VssReadMixin {}
