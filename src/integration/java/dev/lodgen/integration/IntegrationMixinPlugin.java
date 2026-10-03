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
        if (Boolean.getBoolean("lodgen.test.originalPredicates") && mixin.endsWith("DiskFeatureMixin")) return false;
        if (Boolean.getBoolean("lodgen.test.baseline") && mixin.substring(mixin.lastIndexOf('.') + 1).startsWith("Dh")) return false;
        return delegate.shouldApplyMixin(target, mixin);
    }
    @Override public String getRefMapperConfig() { return delegate.getRefMapperConfig(); }
    @Override public void acceptTargets(Set<String> own, Set<String> other) { delegate.acceptTargets(own, other); }
    @Override public List<String> getMixins() { return delegate.getMixins(); }
    @Override public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) { delegate.preApply(target, node, mixin, info); }
    @Override public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        delegate.postApply(target, node, mixin, info);
        if (!Boolean.getBoolean("lodgen.test.verifyPredicates") || !mixin.endsWith("DiskFeatureMixin")) return;
        // Instrument only the packaged test fixture. Production has no verifier
        // branch or extra predicate evaluation in its hot path.
        for (var method : node.methods) {
            if (method.name.contains("lodgen$columns")) {
                var args = org.objectweb.asm.Type.getArgumentTypes(method.desc);
                org.objectweb.asm.tree.MethodInsnNode height = null;
                String worldType = null;
                for (var instruction : method.instructions.toArray()) {
                    if (instruction instanceof org.objectweb.asm.tree.MethodInsnNode call) {
                        if (call.desc.equals("()I")) height = call;
                        if (call.name.equals("rejectsDisk")) worldType = org.objectweb.asm.Type.getArgumentTypes(call.desc)[0].getDescriptor();
                    }
                }
                if (height == null || worldType == null) throw new AssertionError("Disk palette verifier could not locate its inputs");
                for (var instruction : method.instructions.toArray()) {
                    if (instruction.getOpcode() != org.objectweb.asm.Opcodes.ARETURN) continue;
                    var probe = new org.objectweb.asm.tree.InsnList();
                    probe.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 1));
                    probe.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, 2));
                    probe.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, args.length == 3 ? 5 : 3));
                    probe.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, args.length == 3 ? 4 : 7));
                    probe.add(new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.DUP));
                    probe.add(new org.objectweb.asm.tree.MethodInsnNode(height.getOpcode(), height.owner, height.name, height.desc, height.itf));
                    probe.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                            "dev/lodgen/integration/DiskPredicateCheck", "verifyColumns",
                            "(Ljava/lang/Iterable;" + args[0].getDescriptor() + args[1].getDescriptor()
                                    + worldType + "Ljava/lang/Object;I)Ljava/lang/Iterable;", false));
                    method.instructions.insertBefore(instruction, probe);
                }
                method.maxStack = Math.max(method.maxStack, 7);
                continue;
            }
            if (!method.name.contains("lodgen$testTarget")) continue;
            String predicateType = org.objectweb.asm.Type.getArgumentTypes(method.desc)[0].getDescriptor();
            for (var instruction : method.instructions.toArray()) {
                if (instruction.getOpcode() != org.objectweb.asm.Opcodes.IRETURN) continue;
                var probe = new org.objectweb.asm.tree.InsnList();
                for (int index = 1; index <= 3; index++) probe.add(new org.objectweb.asm.tree.VarInsnNode(org.objectweb.asm.Opcodes.ALOAD, index));
                probe.add(new org.objectweb.asm.tree.MethodInsnNode(org.objectweb.asm.Opcodes.INVOKESTATIC,
                        "dev/lodgen/integration/DiskPredicateCheck", "verify",
                        "(Z" + predicateType + "Ljava/lang/Object;Ljava/lang/Object;)Z", false));
                method.instructions.insertBefore(instruction, probe);
            }
            method.maxStack = Math.max(method.maxStack, 4);
        }
    }
}
