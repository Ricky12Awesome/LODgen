// #if NEOFORGE
package dev.ricky12awesome.lodgen;

import net.neoforged.fml.loading.FMLLoader;

public final class ModSupport {
    public static boolean loaded(String id) {
        // #if MC_1211
        return FMLLoader.getLoadingModList().getModFileById(id) != null;
        // #else
        return FMLLoader.getCurrent().getLoadingModList().getModFileById(id) != null;
        // #endif
    }
}
// #endif
