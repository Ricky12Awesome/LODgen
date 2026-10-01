package dev.dhc2me.integration.neoforge;

import dev.dhc2me.integration.IntegrationCheck;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid = "dhc2me")
public final class IntegrationEvents {
    @SubscribeEvent public static void started(ServerStartedEvent event) { IntegrationCheck.run(event.getServer()); }
}
