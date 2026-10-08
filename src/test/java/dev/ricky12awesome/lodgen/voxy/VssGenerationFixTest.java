package dev.ricky12awesome.lodgen.voxy;

import dev.ricky12awesome.lodgen.minecraft.RegionReadAccess;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.Proxy;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VssGenerationFixTest {
    private static final String ACCESSOR = "dev/vox/lss/mixin/AccessorRegionFileStorage";

    @Test void missesSkipAccessorAndSavedRegionsRetainRawLookupWithDifferentFutureSlots() throws Exception {
        for (boolean wideArgument : new boolean[]{false, true}) {
            var loader = new FixtureLoader();
            var api = new ClassWriter(0);
            api.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_INTERFACE,
                    ACCESSOR, null, "java/lang/Object", null);
            api.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "lss$getRegionFile",
                    "(Ljava/lang/Object;)Ljava/lang/Object;", null, null).visitEnd();
            api.visitEnd();
            Class<?> accessor = loader.define(api.toByteArray());
            var node = task(wideArgument);
            VssGenerationFix.guardMissingRawRegions(node);
            // Deliberately retain emitted frames: verify the transformer does not
            // depend on an eventual COMPUTE_FRAMES pass by the mixin runtime.
            var writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            Class<?> task = loader.define(writer.toByteArray());
            var reads = new AtomicInteger();
            boolean[] missing = {true};
            Object storage = Proxy.newProxyInstance(loader, new Class<?>[]{accessor, RegionReadAccess.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("lodgen$missingRegion")) return missing[0];
                        reads.incrementAndGet();
                        return new Object();
                    });
            var parameterTypes = wideArgument
                    ? new Class<?>[]{long.class, Object.class, Object.class, CompletableFuture.class}
                    : new Class<?>[]{Object.class, Object.class, CompletableFuture.class};
            var method = task.getMethod("fetch", parameterTypes);
            for (boolean absent : new boolean[]{true, false}) {
                missing[0] = absent;
                var future = new CompletableFuture<>();
                Object[] arguments = wideArgument
                        ? new Object[]{3L, storage, new Object(), future}
                        : new Object[]{storage, new Object(), future};
                method.invoke(null, arguments);
                assertEquals(Optional.empty(), future.join());
                assertEquals(absent ? 0 : 1, reads.get());
            }
        }
    }

    @Test void unsupportedTaskShapeFailsInsteadOfInstallingAnUnsafeGuard() {
        var node = task(false);
        node.methods.getFirst().desc = "(Ljava/lang/Object;Ljava/lang/Object;)V";
        assertThrows(IllegalStateException.class, () -> VssGenerationFix.guardMissingRawRegions(node));
    }

    private static ClassNode task(boolean wideArgument) {
        var node = new ClassNode();
        node.version = Opcodes.V17;
        node.access = Opcodes.ACC_PUBLIC;
        node.name = "fixture/RawRegionTask";
        node.superName = "java/lang/Object";
        String descriptor = wideArgument
                ? "(JLjava/lang/Object;Ljava/lang/Object;Ljava/util/concurrent/CompletableFuture;)V"
                : "(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/concurrent/CompletableFuture;)V";
        var method = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "fetch", descriptor, null, null);
        int storage = wideArgument ? 2 : 0;
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, storage));
        method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, ACCESSOR));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, storage + 1));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, ACCESSOR, "lss$getRegionFile",
                "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new VarInsnNode(Opcodes.ALOAD, storage + 2));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/util/Optional", "empty",
                "()Ljava/util/Optional;", false));
        method.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/util/concurrent/CompletableFuture",
                "complete", "(Ljava/lang/Object;)Z", false));
        method.instructions.add(new InsnNode(Opcodes.POP));
        method.instructions.add(new InsnNode(Opcodes.RETURN));
        node.methods.add(method);
        return node;
    }

    private static class FixtureLoader extends ClassLoader {
        FixtureLoader() { super(VssGenerationFixTest.class.getClassLoader()); }
        Class<?> define(byte[] bytes) { return defineClass(null, bytes, 0, bytes.length); }
    }
}
