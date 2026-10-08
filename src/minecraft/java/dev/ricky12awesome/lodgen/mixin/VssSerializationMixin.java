package dev.ricky12awesome.lodgen.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The plugin binds VSS masking to the target while retaining source-world lighting. */
@Pseudo
@Mixin(targets = "dev.vox.lss.networking.server.SectionSerializer", remap = false)
public abstract class VssSerializationMixin {}
