package dev.lodgen.mixin;

import dev.lodgen.minecraft.PersistenceRegistry;
import net.minecraft.server.level.Ticket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
// #if MC_1211
import net.minecraft.server.level.DistanceManager;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(DistanceManager.class)
// #else
import net.minecraft.world.level.TicketStorage;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(TicketStorage.class)
// #endif
public abstract class TicketMixin {
    // #if MC_1211
    @Inject(method = "addTicket(JLnet/minecraft/server/level/Ticket;)V", at = @At("HEAD"))
    private void lodgen$promote(long pos, Ticket<?> ticket, CallbackInfo ci) {
    // #else
    @Inject(method = "addTicket(JLnet/minecraft/server/level/Ticket;)Z", at = @At("HEAD"))
    private void lodgen$promote(long pos, Ticket ticket, CallbackInfoReturnable<Boolean> ci) {
    // #endif
        PersistenceRegistry.normalTicket(this, pos, ticket);
    }
}
