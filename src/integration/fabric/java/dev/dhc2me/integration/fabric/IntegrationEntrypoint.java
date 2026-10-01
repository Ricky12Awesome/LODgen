package dev.dhc2me.integration.fabric;

import dev.dhc2me.integration.IntegrationCheck;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

public final class IntegrationEntrypoint implements ModInitializer {
    @Override public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(IntegrationCheck::run);
    }
}
