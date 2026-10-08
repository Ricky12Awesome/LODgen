package dev.ricky12awesome.lodgen.integration.neoforge;

import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

@EventBusSubscriber(modid = "lodgen")
public final class IntegrationEvents {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (Boolean.getBoolean("lodgen.test.vss"))
            dev.ricky12awesome.lodgen.integration.VssCheck.run(event.getServer());
        else runIntegrationCheck(event.getServer());
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) {
        dev.ricky12awesome.lodgen.minecraft.ShutdownCheck.stopped(event.getServer());
    }

    private static void runIntegrationCheck(MinecraftServer server) {
        try {
            Class.forName("dev.ricky12awesome.lodgen.integration.IntegrationCheck", true,
                    IntegrationEvents.class.getClassLoader()).getMethod("run", MinecraftServer.class).invoke(null, server);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot load LODgen integration check", error);
        }
    }
}
