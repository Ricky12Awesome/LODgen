package dev.ricky12awesome.lodgen.mixin;

import net.minecraft.world.level.levelgen.blockpredicates.BlockPredicate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(targets = "net.minecraft.world.level.levelgen.blockpredicates.CombiningPredicate")
public interface CombiningPredicateAccess {
    @Accessor("predicates") List<BlockPredicate> lodgen$predicates();
}
