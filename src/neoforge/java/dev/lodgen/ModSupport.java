package dev.lodgen;

import net.neoforged.fml.loading.LoadingModList;

public final class ModSupport {
    public static boolean loaded(String id) { return LoadingModList.get().getModFileById(id) != null; }
}
