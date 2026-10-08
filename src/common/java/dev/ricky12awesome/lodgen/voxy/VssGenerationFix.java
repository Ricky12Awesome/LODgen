package dev.ricky12awesome.lodgen.voxy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;

/** VSS reloads read generation directly, including in compiler-generated callbacks. */
public final class VssGenerationFix {
    /** Keep the native executor and saved-region raw path, but never create files on misses. */
    public static void guardMissingRawRegions(ClassNode node) {
        int guarded = 0;
        for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("dev/vox/lss/mixin/AccessorRegionFileStorage")
                    || !call.name.equals("lss$getRegionFile")) continue;
            var arguments = org.objectweb.asm.Type.getArgumentTypes(method.desc);
            var lookupArguments = org.objectweb.asm.Type.getArgumentTypes(call.desc);
            if (org.objectweb.asm.Type.getReturnType(method.desc).getSort() != org.objectweb.asm.Type.VOID
                    || call.getOpcode() != Opcodes.INVOKEINTERFACE || lookupArguments.length != 1
                    || lookupArguments[0].getSort() != org.objectweb.asm.Type.OBJECT
                    || org.objectweb.asm.Type.getReturnType(call.desc).getSort() != org.objectweb.asm.Type.OBJECT)
                throw new IllegalStateException("Unsupported VSS raw region lookup: " + method.name + method.desc);
            int local = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
            int future = -1;
            var frameLocals = new java.util.ArrayList<Object>();
            if (local == 1) frameLocals.add(node.name);
            for (var argument : arguments) {
                if (argument.getDescriptor().equals("Ljava/util/concurrent/CompletableFuture;")) {
                    if (future != -1) throw new IllegalStateException("Ambiguous VSS raw-read future");
                    future = local;
                }
                frameLocals.add(frameType(argument));
                local += argument.getSize();
            }
            if (future < 0) throw new IllegalStateException("Missing VSS raw-read future: " + method.name);
            // The accessor is the first expression in the task. Fail rather than emit
            // an inaccurate continuation frame if an upstream version changes that shape.
            for (var preceding = method.instructions.getFirst(); preceding != call; preceding = preceding.getNext()) {
                int opcode = preceding.getOpcode();
                if ((opcode >= Opcodes.ISTORE && opcode <= Opcodes.ASTORE)
                        || preceding instanceof org.objectweb.asm.tree.IincInsnNode
                        || preceding instanceof org.objectweb.asm.tree.JumpInsnNode)
                    throw new IllegalStateException("Unsupported VSS raw-read task locals: " + method.name);
            }
            var proceed = new org.objectweb.asm.tree.LabelNode();
            var guard = new InsnList();
            guard.add(new InsnNode(Opcodes.DUP2));
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/ricky12awesome/lodgen/minecraft/RegionReadAccess", "missing",
                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", true));
            guard.add(new org.objectweb.asm.tree.JumpInsnNode(Opcodes.IFEQ, proceed));
            guard.add(new InsnNode(Opcodes.POP2));
            guard.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.ALOAD, future));
            guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty",
                    "()Ljava/util/Optional;", false));
            guard.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture",
                    "complete", "(Ljava/lang/Object;)Z", false));
            guard.add(new InsnNode(Opcodes.POP));
            guard.add(new InsnNode(Opcodes.RETURN));
            guard.add(proceed);
            guard.add(new org.objectweb.asm.tree.FrameNode(Opcodes.F_NEW, frameLocals.size(),
                    frameLocals.toArray(), 2, new Object[]{call.owner, lookupArguments[0].getInternalName()}));
            method.instructions.insertBefore(call, guard);
            method.maxStack = Math.max(method.maxStack, 4);
            guarded++;
        }
        if (guarded != 1) throw new IllegalStateException("Expected one VSS raw region lookup, found " + guarded);
    }

    private static Object frameType(org.objectweb.asm.Type type) {
        return switch (type.getSort()) {
            case org.objectweb.asm.Type.BOOLEAN, org.objectweb.asm.Type.BYTE, org.objectweb.asm.Type.CHAR,
                    org.objectweb.asm.Type.SHORT, org.objectweb.asm.Type.INT -> Opcodes.INTEGER;
            case org.objectweb.asm.Type.FLOAT -> Opcodes.FLOAT;
            case org.objectweb.asm.Type.LONG -> Opcodes.LONG;
            case org.objectweb.asm.Type.DOUBLE -> Opcodes.DOUBLE;
            case org.objectweb.asm.Type.ARRAY -> type.getDescriptor();
            case org.objectweb.asm.Type.OBJECT -> type.getInternalName();
            default -> throw new IllegalStateException("Unsupported VSS raw-read argument: " + type);
        };
    }

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
