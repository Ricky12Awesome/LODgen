package dev.ricky12awesome.lodgen.voxy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** VSS reloads read generation directly, including in compiler-generated callbacks. */
public final class VssGenerationFix {
    public static void bindTargetMask(ClassNode node) {
        for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("dev/vox/lss/networking/server/XrayMaskManager")
                    || !call.name.equals("entryForActive")) continue;
            var level = org.objectweb.asm.Type.getArgumentTypes(call.desc)[0];
            method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/ricky12awesome/lodgen/voxy/VssGeneration", "maskLevel",
                    org.objectweb.asm.Type.getMethodDescriptor(level, level), false));
        }
    }

    public static void disableReloadGeneration(ClassNode node) {
        for (var method : node.methods) {
            if (!method.name.equals("reconcileSettings") && !method.name.startsWith("lambda$reconcileSettings$")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)
                        || !call.owner.equals("dev/vox/lss/common/config/ServerSettings$Generation")
                        || !call.name.equals("enabled") || !call.desc.equals("()Z")) continue;
                var replacement = new InsnList();
                replacement.add(new InsnNode(Opcodes.POP));
                replacement.add(new InsnNode(Opcodes.ICONST_0));
                method.instructions.insertBefore(call, replacement);
                method.instructions.remove(call);
            }
        }
    }
}
