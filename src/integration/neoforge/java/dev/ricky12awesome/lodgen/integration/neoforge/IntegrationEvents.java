package dev.ricky12awesome.lodgen.integration.neoforge;

import dev.ricky12awesome.lodgen.integration.IntegrationCheck;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid = "lodgen")
public final class IntegrationEvents {
    @SubscribeEvent public static void started(ServerStartedEvent event) { IntegrationCheck.run(event.getServer()); }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        dev.ricky12awesome.lodgen.minecraft.ShutdownCheck.stopped(event.getServer());
    }
}
