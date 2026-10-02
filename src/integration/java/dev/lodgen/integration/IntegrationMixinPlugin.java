package dev.lodgen.integration;

import dev.lodgen.mixin.LodgenMixinPlugin;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** The test runner can measure DH's original generator without redefining the
 * production automatic-generation toggle. This plugin is absent from releases.
 */
public final class IntegrationMixinPlugin implements IMixinConfigPlugin {
    private final LodgenMixinPlugin delegate = new LodgenMixinPlugin();
    @Override public void onLoad(String mixinPackage) { delegate.onLoad(mixinPackage); }
    @Override public boolean shouldApplyMixin(String target, String mixin) {
        if (Boolean.getBoolean("lodgen.test.baseline") && mixin.substring(mixin.lastIndexOf('.') + 1).startsWith("Dh")) return false;
        return delegate.shouldApplyMixin(target, mixin);
    }
    @Override public String getRefMapperConfig() { return delegate.getRefMapperConfig(); }
    @Override public void acceptTargets(Set<String> own, Set<String> other) { delegate.acceptTargets(own, other); }
    @Override public List<String> getMixins() { return delegate.getMixins(); }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { delegate.preApply(target, node, mixin, info); }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) { delegate.postApply(target, node, mixin, info); }
}
