package dev.lodgen.integration.neoforge;

import dev.lodgen.integration.IntegrationCheck;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid = "lodgen")
public final class IntegrationEvents {
    @SubscribeEvent public static void started(ServerStartedEvent event) { IntegrationCheck.run(event.getServer()); }
}
