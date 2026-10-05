package dev.ricky12awesome.lodgen.integration.fabric;

import dev.ricky12awesome.lodgen.integration.IntegrationCheck;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class IntegrationEntrypoint implements ModInitializer {
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(IntegrationCheck::run);
        ServerLifecycleEvents.SERVER_STOPPED.register(dev.ricky12awesome.lodgen.minecraft.ShutdownCheck::stopped);
    }
}
