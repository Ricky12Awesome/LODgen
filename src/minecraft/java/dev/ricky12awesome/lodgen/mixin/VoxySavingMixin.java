package dev.ricky12awesome.lodgen.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/** The plugin repairs the old saver before this optional mixin is applied. */
@Pseudo
@Mixin(targets = "me.cortex.voxy.common.world.service.SectionSavingService", remap = false)
public abstract class VoxySavingMixin {}
