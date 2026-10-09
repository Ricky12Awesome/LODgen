package dev.ricky12awesome.lodgen.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;
import java.util.UUID;

/** Register the packet probe without a socket or a loader-specific fake player. */
@Mixin(PlayerList.class)
public interface VssPlayerListAccessor {
    @Accessor("playersByUUID")
    Map<UUID, ServerPlayer> lodgen$playersByUUID();
}
