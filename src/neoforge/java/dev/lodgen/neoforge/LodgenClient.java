package dev.lodgen.neoforge;

import dev.lodgen.client.LodgenConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

@Mod(value = "lodgen", dist = Dist.CLIENT)
public final class LodgenClient {
    public LodgenClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new LodgenConfigScreen(parent));
    }
}
