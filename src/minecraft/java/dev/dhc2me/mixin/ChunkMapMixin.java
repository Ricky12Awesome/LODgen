package dev.dhc2me.mixin;

import dev.dhc2me.minecraft.PersistenceRegistry;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
// #if MC_1211
import net.minecraft.server.level.DistanceManager;
// #else
import net.minecraft.world.level.TicketStorage;
// #endif

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {
    @Shadow @Final private ServerLevel level;
    // #if MC_1211
    @Shadow public abstract DistanceManager getDistanceManager();
    // #else
    @Shadow @Final private TicketStorage ticketStorage;
    // #endif

    @Inject(method = "<init>", at = @At("RETURN"))
    private void dhc2me$register(CallbackInfo ci) {
        // #if MC_1211
        PersistenceRegistry.register(level, getDistanceManager());
        // #else
        PersistenceRegistry.register(level, ticketStorage);
        // #endif
    }
}
