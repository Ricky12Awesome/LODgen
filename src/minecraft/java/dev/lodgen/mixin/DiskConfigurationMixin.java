package dev.lodgen.mixin;

import dev.lodgen.minecraft.DiskPredicatePlan;
import dev.lodgen.minecraft.DiskPredicateAccess;
// #if MC_263
import net.minecraft.world.level.levelgen.feature.DiskFeature;
// #else
import net.minecraft.world.level.levelgen.feature.configurations.DiskConfiguration;
// #endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// #if MC_263
@Mixin(DiskFeature.class)
// #else
@Mixin(DiskConfiguration.class)
// #endif
public abstract class DiskConfigurationMixin implements DiskPredicateAccess {
    @Unique private DiskPredicatePlan lodgen$targetPlan;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void lodgen$compileTarget(CallbackInfo callback) {
        // #if MC_263
        lodgen$targetPlan = DiskPredicatePlan.compile(((DiskFeature) (Object) this).target());
        // #else
        lodgen$targetPlan = DiskPredicatePlan.compile(((DiskConfiguration) (Object) this).target());
        // #endif
    }

    @Override public DiskPredicatePlan lodgen$targetPlan() { return lodgen$targetPlan; }
}
