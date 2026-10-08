package dev.ricky12awesome.lodgen.voxy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

/** Change only native generation execution; derive Minecraft descriptors from VSS. */
public final class VssGenerationFix {
    private static final String BRIDGE = "dev/ricky12awesome/lodgen/voxy/VssGeneration";
    public static void nativeGeneration(ClassNode node) {
        int additions = 0, polls = 0, serializers = 0, removals = 0;
        String ticketType = node.fields.stream().filter(field -> field.name.equals("LSS_GEN_TICKET"))
                .map(field -> field.desc).findFirst().orElse(null);
        for (var method : node.methods) {
            if (method.name.equals("tick")) {
                var before = new InsnList();
                before.add(new VarInsnNode(Opcodes.ALOAD, 0));
                before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "beforeTick", "(Ljava/lang/Object;)V", false));
                method.instructions.insert(before);
            }
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                var parameters = Type.getArgumentTypes(call.desc);
                boolean ticketCall = ticketType != null && (parameters.length == 3 || parameters.length == 4)
                        && parameters[0].getDescriptor().equals(ticketType)
                        && parameters[2].getSort() == Type.INT && Type.getReturnType(call.desc).getSort() == Type.VOID;
                boolean add = ticketCall && method.name.equals("submitGeneration")
                        || call.name.equals("addRegionTicket") || call.name.equals("addTicketWithRadius");
                boolean remove = ticketCall && !method.name.equals("submitGeneration")
                        || call.name.equals("removeRegionTicket") || call.name.equals("removeTicketWithRadius");
                if (add || remove) {
                    var arguments = Type.getArgumentTypes(call.desc);
                    if (arguments.length != 3 && arguments.length != 4)
                        throw new IllegalStateException("Unsupported VSS generation ticket: " + call.desc);
                    String descriptor = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;I"
                            + (arguments.length == 4 ? "Ljava/lang/Object;" : "")
                            + (add ? "Ljava/lang/Object;" : "") + ")V";
                    if (add) method.instructions.insertBefore(call, new VarInsnNode(Opcodes.ALOAD, 0));
                    method.instructions.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                            (add ? "add" : "remove") + arguments.length, descriptor, false));
                    if (add) additions++; else removals++;
                    method.maxStack++;
                } else if (call.owner.endsWith("DeferredTicketReleases") && call.name.equals("cancel")) {
                    // Reusing a native deferred ticket must still revisit our backend:
                    // a failed generation may need a fresh request. Cancel the original
                    // release, then let the original admission execute its add branch.
                    var revisit = new InsnList();
                    revisit.add(new InsnNode(Opcodes.POP));
                    revisit.add(new InsnNode(Opcodes.ICONST_0));
                    method.instructions.insert(call, revisit);
                } else if (method.name.equals("tick") && call.desc.startsWith("(II)")
                        && Type.getReturnType(call.desc).getSort() == Type.OBJECT) {
                    var replacement = new InsnList();
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "chunk",
                            "(Ljava/lang/Object;II)Ljava/lang/Object;", false));
                    replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getReturnType(call.desc).getInternalName()));
                    method.instructions.insertBefore(call, replacement); method.instructions.remove(call); polls++;
                } else if (call.owner.endsWith("ChunkGenerationService$ColumnSerializer") && call.name.equals("serialize")) {
                    var replacement = new InsnList();
                    replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, "serialize",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;II)Ljava/lang/Object;", false));
                    replacement.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getReturnType(call.desc).getInternalName()));
                    method.instructions.insertBefore(call, replacement); method.instructions.remove(call); serializers++;
                }
            }
        }
        if (additions != 1 || polls != 1 || serializers != 1 || removals < 3)
            throw new IllegalStateException("Unsupported VSS native generation service shape");
    }
    /** Direct snapshot reads during live reload must use the same effective policy. */
    public static void nativeRuntimePolicy(ClassNode node) {
        for (var method : node.methods) {
            if (!method.name.equals("reconcileSettings") && !method.name.startsWith("lambda$reconcileSettings$")) continue;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call)) continue;
                boolean generation = call.owner.equals("dev/vox/lss/common/config/ServerSettings$Generation");
                boolean concurrency = call.owner.equals("dev/vox/lss/common/config/ServerSettings$GenerationConcurrency");
                boolean enabled = generation && call.name.equals("enabled") && call.desc.equals("()Z");
                boolean unlimited = generation && call.name.equals("timeoutTicks") && call.desc.equals("()I")
                        || concurrency && (call.name.equals("global") || call.name.equals("perPlayer")) && call.desc.equals("()I");
                if (!enabled && !unlimited) continue;
                var replacement = new InsnList();
                replacement.add(new InsnNode(Opcodes.POP));
                replacement.add(enabled ? new InsnNode(Opcodes.ICONST_1) : new LdcInsnNode(Integer.MAX_VALUE));
                method.instructions.insertBefore(call, replacement); method.instructions.remove(call);
            }
        }
    }
    public static void bindTargetMask(ClassNode node) {
        for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
            if (!(instruction instanceof MethodInsnNode call)
                    || !call.owner.equals("dev/vox/lss/networking/server/XrayMaskManager")
                    || !call.name.equals("entryForActive")) continue;
            var level = Type.getArgumentTypes(call.desc)[0];
            method.instructions.insertBefore(call, new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE,
                    "maskLevel", Type.getMethodDescriptor(level, level), false));
        }
    }
}
