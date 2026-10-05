package dev.ricky12awesome.lodgen.fabric;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.ricky12awesome.lodgen.client.LodgenConfigScreen;

/** Optional Mod Menu entry; reuses the same screen and parent navigation. */
public final class LodgenModMenu implements ModMenuApi {
    @Override public ConfigScreenFactory<LodgenConfigScreen> getModConfigScreenFactory() {
        return LodgenConfigScreen::new;
    }
}
