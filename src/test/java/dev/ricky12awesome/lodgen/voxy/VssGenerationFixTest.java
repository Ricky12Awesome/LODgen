package dev.ricky12awesome.lodgen.voxy;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class VssGenerationFixTest {
    @Test void nativeHooksDeriveMappedTicketAndChunkTypesForBothTicketApis() {
        for (boolean legacy : new boolean[]{true, false}) {
            var node = fixture(legacy);
            VssGenerationFix.nativeGeneration(node);
            var calls = new ArrayList<MethodInsnNode>();
            for (var method : node.methods) for (var instruction : method.instructions.toArray())
                if (instruction instanceof MethodInsnNode call) calls.add(call);
            assertTrue(calls.stream().noneMatch(call -> call.owner.equals("mapped/Cache")));
            assertTrue(calls.stream().anyMatch(call -> call.name.equals(legacy ? "add4" : "add3")
                    && call.desc.endsWith("Ljava/lang/Object;)V")));
            assertEquals(3, calls.stream().filter(call -> call.name.equals(legacy ? "remove4" : "remove3")).count());
            assertTrue(calls.stream().anyMatch(call -> call.name.equals("beforeTick")));
            assertTrue(calls.stream().anyMatch(call -> call.name.equals("serialize") && call.getOpcode() == Opcodes.INVOKESTATIC));
            assertTrue(java.util.Arrays.stream(node.methods.get(1).instructions.toArray())
                    .anyMatch(instruction -> instruction instanceof TypeInsnNode cast && cast.desc.equals("mapped/FullChunk")));
        }
    }
    @Test void runtimeReloadOverridesDoNotChangeConfigurationSerializationReads() {
        var node = new ClassNode();
        for (String name : new String[]{"reconcileSettings", "lambda$reconcileSettings$3", "values", "save"}) {
            var method = new MethodNode(Opcodes.ACC_PUBLIC, name, "()V", null, null);
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "dev/vox/lss/common/config/ServerSettings$Generation", "enabled", "()Z", false));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "dev/vox/lss/common/config/ServerSettings$Generation", "timeoutTicks", "()I", false));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "dev/vox/lss/common/config/ServerSettings$GenerationConcurrency", "perPlayer", "()I", false));
            node.methods.add(method);
        }
        VssGenerationFix.nativeRuntimePolicy(node);
        for (var method : node.methods) {
            boolean runtime = method.name.contains("reconcileSettings");
            long calls = java.util.Arrays.stream(method.instructions.toArray()).filter(instruction -> instruction instanceof MethodInsnNode).count();
            assertEquals(runtime ? 0 : 3, calls);
            if (runtime) assertTrue(java.util.Arrays.stream(method.instructions.toArray())
                    .anyMatch(instruction -> instruction instanceof LdcInsnNode constant && constant.cst.equals(Integer.MAX_VALUE)));
        }
    }
    @Test void unsupportedNativeServiceFailsClosed() {
        assertThrows(IllegalStateException.class, () -> VssGenerationFix.nativeGeneration(new ClassNode()));
    }
    private static ClassNode fixture(boolean legacy) {
        var node = new ClassNode(); node.name = "fixture/NativeService";
        var submit = new MethodNode(Opcodes.ACC_PUBLIC, "submitGeneration", "()V", null, null);
        var tick = new MethodNode(Opcodes.ACC_PUBLIC, "tick", "()Ljava/util/List;", null, null);
        var shutdown = new MethodNode(Opcodes.ACC_PUBLIC, "shutdown", "()V", null, null);
        var deferred = new MethodNode(Opcodes.ACC_STATIC, "lambda$removePlayer$2", "()V", null, null);
        node.methods.add(submit); node.methods.add(tick); node.methods.add(shutdown); node.methods.add(deferred);
        String ticket = "(Lmapped/Ticket;Lmapped/Position;I" + (legacy ? "Ljava/lang/Object;" : "") + ")V";
        submit.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "mapped/Cache", legacy ? "addRegionTicket" : "addTicketWithRadius", ticket, false));
        tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "mapped/Cache", "getChunkNow", "(II)Lmapped/FullChunk;", false));
        tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "dev/vox/lss/networking/server/ChunkGenerationService$ColumnSerializer", "serialize",
                "(Lmapped/World;Lmapped/FullChunk;II)Lnative/Column;", true));
        for (var method : new MethodNode[]{tick, shutdown, deferred})
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "mapped/Cache", legacy ? "removeRegionTicket" : "removeTicketWithRadius", ticket, false));
        return node;
    }
}
