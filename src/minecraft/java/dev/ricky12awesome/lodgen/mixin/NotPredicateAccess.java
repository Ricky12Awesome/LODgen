package dev.ricky12awesome.lodgen.mixin;

import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.NotPredicate")
public interface NotPredicateAccess {
    @Accessor("predicate") BlockPredicate lodgen$predicate();
}
