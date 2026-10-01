package dev.lodgen.mixin;

import dev.lodgen.ModSupport;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Properties;
import java.util.Set;

/** Keep absent renderers out of Minecraft's transformation and class loading. */
public final class LodgenMixinPlugin implements IMixinConfigPlugin {
    private boolean voxy;

    @Override public void onLoad(String mixinPackage) {
        var target = new Properties();
        try (var stream = getClass().getResourceAsStream("/lodgen.target.properties")) {
            target.load(stream);
        } catch (Exception error) { throw new IllegalStateException("Missing LODgen target metadata", error); }
        String mc = target.getProperty("minecraft"), loader = target.getProperty("loader");
        voxy = (mc.equals("1.21.1") && loader.equals("neoforge") && ModSupport.loaded("roxy"))
                || (loader.equals("fabric") && (mc.equals("26.1.2") || mc.equals("26.2")) && ModSupport.loaded("voxy"));
    }

    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String name = mixinClassName.substring(mixinClassName.lastIndexOf('.') + 1);
        if (name.startsWith("Dh")) return ModSupport.loaded("distanthorizons");
        if (name.startsWith("Voxy")) return voxy;
        return true;
    }
    @Override public String getRefMapperConfig() { return null; }
    @Override public void acceptTargets(Set<String> own, Set<String> other) {}
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {}
}
