package dev.ricky12awesome.lodgen;

import net.fabricmc.loader.api.FabricLoader;

public final class ModSupport {
    public static boolean loaded(String id) { return FabricLoader.getInstance().isModLoaded(id); }
}
