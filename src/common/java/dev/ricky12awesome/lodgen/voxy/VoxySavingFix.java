package dev.ricky12awesome.lodgen.voxy;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** Backport Voxy's save-queue flag ordering without changing already-fixed jars. */
public final class VoxySavingFix {
    private static final String SECTION = "me/cortex/voxy/common/world/WorldSection";

    public static boolean repair(ClassNode node) {
        for (var method : node.methods) {
            if (!method.name.equals("processJob") || !method.desc.equals("()V")) continue;
            MethodInsnNode dirty = null;
            for (var instruction : method.instructions.toArray()) {
                if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(SECTION)) continue;
                if (call.name.equals("setNotDirty") && call.desc.equals("()Z")) dirty = call;
                if (!call.name.equals("exchangeIsInSaveQueue") || !call.desc.equals("(Z)Z")) continue;
                if (dirty == null) return false; // Upstream already clears the queue first.
                var load = previous(dirty);
                var pop = next(dirty);
                var value = previous(call);
                var queueLoad = previous(value);
                if (!(load instanceof VarInsnNode section) || section.getOpcode() != Opcodes.ALOAD
                        || pop == null || pop.getOpcode() != Opcodes.POP
                        || !(queueLoad instanceof VarInsnNode queued) || queued.getOpcode() != Opcodes.ALOAD
                        || queued.var != section.var || value.getOpcode() != Opcodes.ICONST_0
                        || next(pop) != queueLoad) {
                    throw new IllegalStateException("Unrecognized Voxy save flag ordering");
                }
                // Keep the exchange result on the stack for its existing branch.
                // Clearing dirty afterwards prevents a concurrent update from
                // observing the old queue flag and skipping its next save.
                method.instructions.remove(load);
                method.instructions.remove(dirty);
                method.instructions.remove(pop);
                method.instructions.insert(call, pop);
                method.instructions.insert(call, dirty);
                method.instructions.insert(call, load);
                method.maxStack = Math.max(method.maxStack, 2);
                return true;
            }
        }
        return false;
    }

    private static AbstractInsnNode previous(AbstractInsnNode instruction) {
        if (instruction == null) return null;
        do { instruction = instruction.getPrevious(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }

    private static AbstractInsnNode next(AbstractInsnNode instruction) {
        do { instruction = instruction.getNext(); } while (instruction != null && instruction.getOpcode() < 0);
        return instruction;
    }
}
