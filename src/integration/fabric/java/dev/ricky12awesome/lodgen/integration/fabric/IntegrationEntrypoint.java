package dev.ricky12awesome.lodgen.integration.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;

public final class IntegrationEntrypoint implements ModInitializer {
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (Boolean.getBoolean("lodgen.test.vss"))
                dev.ricky12awesome.lodgen.integration.VssCheck.run(server);
            else runIntegrationCheck(server);
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(dev.ricky12awesome.lodgen.minecraft.ShutdownCheck::stopped);
    }

    private static void runIntegrationCheck(MinecraftServer server) {
        try {
            Class.forName("dev.ricky12awesome.lodgen.integration.IntegrationCheck", true,
                    IntegrationEntrypoint.class.getClassLoader()).getMethod("run", MinecraftServer.class).invoke(null, server);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Cannot load LODgen integration check", error);
        }
    }
}
