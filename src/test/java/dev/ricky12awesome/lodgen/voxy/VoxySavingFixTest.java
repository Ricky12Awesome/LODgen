package dev.ricky12awesome.lodgen.voxy;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

public class VoxySavingFixTest {
    private static final String VOXY_SECTION = "me/cortex/voxy/common/world/WorldSection";

    @Test void concurrentUpdateReproducesTheOriginalDirtySectionCrash() {
        var section = updatedDuringDirtyClear();
        assertThrows(IllegalStateException.class, () -> new OldSaver(section).processJob());
        assertTrue(section.dirty);
        assertFalse(section.queued);
    }

    @Test void repairedBytecodeRetainsTheNewSaveAndPersistsTheUpdate() throws Exception {
        var node = saver(OldSaver.class);
        assertTrue(VoxySavingFix.repair(node));
        assertFalse(VoxySavingFix.repair(node), "The compatibility repair must be idempotent");
        var section = updatedDuringDirtyClear();
        Object saver = load(node).getConstructor(Section.class).newInstance(section);
        saver.getClass().getMethod("processJob").invoke(saver);
        assertEquals(1, section.references, "Concurrent update must retain a save reference");
        assertTrue(section.queued);
        assertTrue(section.dirty);
        assertEquals(2, section.saved);
        saver.getClass().getMethod("processJob").invoke(saver);
        assertEquals(0, section.references);
        assertFalse(section.queued);
        assertFalse(section.dirty);
        assertEquals(2, section.saved);
    }

    @Test void upstreamFixedSaverIsLeftUntouched() throws Exception {
        var node = saver(FixedSaver.class);
        var before = bytes(node);
        assertFalse(VoxySavingFix.repair(node));
        assertArrayEquals(before, bytes(node));
    }

    @Test void unsafeInstructionShapesAreRejectedBeforeModification() throws Exception {
        var node = saver(OldSaver.class);
        for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.name.equals("setNotDirty")) {
                method.instructions.insert(call, new org.objectweb.asm.tree.InsnNode(org.objectweb.asm.Opcodes.NOP));
            }
        }
        var before = bytes(node);
        assertThrows(IllegalStateException.class, () -> VoxySavingFix.repair(node));
        assertArrayEquals(before, bytes(node));
    }

    private static Section updatedDuringDirtyClear() {
        var section = new Section();
        var updated = new AtomicBoolean();
        section.afterClear = () -> {
            if (updated.compareAndSet(false, true)) {
                section.references++; // Updater owns a reference while changing data.
                section.value = 2;
                section.dirty = true;
                if (section.exchangeIsInSaveQueue(true)) section.references++;
                section.release();
            }
        };
        return section;
    }

    private static ClassNode saver(Class<?> fixture) throws Exception {
        var node = new ClassNode();
        try (var stream = fixture.getResourceAsStream("/" + Type.getInternalName(fixture) + ".class")) {
            new ClassReader(stream).accept(node, 0);
        }
        for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(Type.getInternalName(Section.class)))
                call.owner = VOXY_SECTION;
        }
        return node;
    }

    private static byte[] bytes(ClassNode node) {
        var writer = new ClassWriter(0);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static Class<?> load(ClassNode node) {
        String originalName = node.name;
        node.name = "dev/ricky12awesome/lodgen/voxy/RepairedSaver";
        node.nestHostClass = null;
        for (var method : node.methods) for (var instruction : method.instructions.toArray()) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(VOXY_SECTION))
                call.owner = Type.getInternalName(Section.class);
            if (instruction instanceof FieldInsnNode field && field.owner.equals(originalName)) field.owner = node.name;
        }
        var data = bytes(node);
        return new ClassLoader(VoxySavingFixTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(null, data, 0, data.length); }
        }.define();
    }

    public static class Section {
        public boolean dirty = true, queued = true;
        public int references = 1, value = 1, saved;
        public Runnable afterClear = () -> {};
        public boolean setNotDirty() { boolean old = dirty; dirty = false; afterClear.run(); return old; }
        public boolean exchangeIsInSaveQueue(boolean value) {
            if (queued == value) return false;
            queued = value;
            return true;
        }
        public void save() { saved = value; }
        public void release() {
            if (--references == 0 && (dirty || queued)) throw new IllegalStateException("Section freed while dirty or queued");
        }
    }

    public static class OldSaver {
        public final Section section;
        public OldSaver(Section section) { this.section = section; }
        public void processJob() {
            var current = section;
            current.setNotDirty();
            if (current.exchangeIsInSaveQueue(false)) current.save();
            current.release();
        }
    }

    public static class FixedSaver {
        public final Section section;
        public FixedSaver(Section section) { this.section = section; }
        public void processJob() {
            var current = section;
            if (current.exchangeIsInSaveQueue(false)) {
                current.setNotDirty();
                current.save();
            } else current.setNotDirty();
            current.release();
        }
    }
}
