package dev.lodgen.startup;

import dev.lodgen.voxy.VoxyBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

/** Compare the air fast path with Voxy's converter, including every mip cell,
 * and check inherited sky lighting against the installed native light engine.
 */
final class VoxySnapshotCheck {
    private static Object field(Object target, String name) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    static void verify(ServerLevel level, List<ChunkAccess> chunks) throws Exception {
        var bridge = new VoxyBridge();
        var context = bridge.context(level);
        Object mapper = context.engine().getClass().getMethod("getMapper").invoke(context.engine());
        Class<?> voxel = (Class<?>) field(bridge, "voxel");
        var lightFactory = (MethodHandle) field(bridge, "lightFactory");
        var convert = (Method) field(bridge, "convert");
        var mip = (Method) field(bridge, "mip");
        var fast = VoxyBridge.class.getDeclaredMethod("convertSection", Object.class, Object.class, VoxyBridge.Section.class); fast.setAccessible(true);
        var light = VoxyBridge.class.getDeclaredMethod("light", VoxyBridge.Section.class, int.class, int.class, int.class); light.setAccessible(true);
        var voxels = voxel.getField("section");
        int airSections = 0;
        for (var section : VoxyBridge.snapshot(level, chunks)) {
            for (int x : new int[]{0, 7, 15}) for (int z : new int[]{0, 7, 15}) for (int y : new int[]{0, 15}) {
                var pos = new BlockPos(section.x() * 16 + x, section.y() * 16 + y, section.z() * 16 + z);
                var engine = level.getChunkSource().getLightEngine();
                byte expected = (byte) (engine.getLayerListener(LightLayer.SKY).getLightValue(pos)
                        | engine.getLayerListener(LightLayer.BLOCK).getLightValue(pos) << 4);
                if ((byte) light.invoke(null, section, x, y, z) != expected) throw new AssertionError("Voxy snapshot lighting differs at " + pos);
            }
            Object reference = voxel.getMethod("createEmpty").invoke(null), optimized = voxel.getMethod("createEmpty").invoke(null);
            Object supplier;
            try { supplier = lightFactory.invokeExact(section); }
            catch (Throwable error) { throw new AssertionError(error); }
            convert.invoke(null, reference, mapper, section.data().getStates(), section.data().getBiomes(), supplier);
            mip.invoke(null, reference, mapper);
            fast.invoke(bridge, optimized, mapper, section);
            if (!Arrays.equals((long[]) voxels.get(reference), (long[]) voxels.get(optimized))) throw new AssertionError("Voxy optimized conversion differs from its converter");
            if (section.data().hasOnlyAir()) airSections++;
        }
        if (airSections == 0) throw new AssertionError("Snapshot test did not exercise any air sections");
        dev.lodgen.LodgenConfig.LOGGER.info("VOXY PARITY: native lighting and voxel/mip arrays match the original converter, including {} air sections", airSections);
    }
}
