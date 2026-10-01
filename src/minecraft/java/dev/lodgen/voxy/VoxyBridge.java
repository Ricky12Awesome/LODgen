package dev.lodgen.voxy;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.lang.reflect.Method;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Voxy's public conversion/update entry points are identical across the three
 * pinned jars. Reflection also works with Roxy's remapped 1.21.11 Fabric jar.
 * Minecraft types never appear in reflected method names or descriptors.
 */
public final class VoxyBridge {
    private final Class<?> config = type("client.config.VoxyConfig");
    private final Class<?> common = type("commonImpl.VoxyCommon");
    private final Class<?> identifier = type("commonImpl.WorldIdentifier");
    private final Class<?> voxel = type("common.voxelization.VoxelizedSection");
    private final Class<?> lighting = type("common.voxelization.ILightingSupplier");
    private final MethodHandle lightFactory = lightingFactory(lighting);
    private final Method convert = method(type("common.voxelization.WorldConversionFactory"), "convert", 5);
    private final Method mip = method(type("common.voxelization.WorldVoxilizedSectionMipper"), "mipSection", 2);
    private final Method insert = method(type("common.world.WorldUpdater"), "insertUpdate", 2);

    public record Context(Object engine, Path coverageFile, int radius) {}
    public record Section(int x, int y, int z, LevelChunkSection data, DataLayer block, DataLayer sky, int[] inheritedSky) {}

    public Context context(ClientLevel level) throws ReflectiveOperationException {
        return context(method(identifier, "of", 1).invoke(null, level));
    }

    /** Construct the same dimension/biome-seed identifier without a client-world visit. */
    public Context context(ServerLevel level) throws ReflectiveOperationException {
        Object id = null;
        for (var constructor : identifier.getConstructors()) {
            if (constructor.getParameterCount() == 3) {
                id = constructor.newInstance(level.dimension(), net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(level.getSeed()),
                        level.dimensionTypeRegistration().unwrapKey().orElseThrow());
                break;
            }
        }
        if (id == null) throw new IllegalStateException("Missing Voxy world identifier constructor");
        return context(id);
    }

    private Context context(Object id) throws ReflectiveOperationException {
        Object cfg = config.getField("CONFIG").get(null);
        if (!config.getField("enabled").getBoolean(cfg) || !config.getField("ingestEnabled").getBoolean(cfg)) return null;
        Object instance = method(common, "getInstance", 0).invoke(null);
        if (instance == null) return null;
                if (id == null || !(Boolean) method(instance.getClass(), "isIngestEnabled", 1).invoke(instance, id)) return null;
        Object engine = method(identifier, "getOrCreateEngine", 0).invoke(id);
        if (engine == null) return null;
        method(engine.getClass(), "markActive", 0).invoke(engine);
        Path base = (Path) method(instance.getClass(), "getStorageBasePath", 0).invoke(instance);
        String worldId = (String) method(identifier, "getWorldId", 0).invoke(id);
        int radius = Math.max(1, Math.round(config.getField("sectionRenderDistance").getFloat(cfg) * 32));
        return new Context(engine, base.resolve("lodgen").resolve(worldId + ".tiles"), radius);
    }

    /** Snapshot on the server thread. Voxy workers must never read mutable
     * palettes or lighting from chunks that players/Chunky can adopt and modify.
     */
    public static List<Section> snapshot(ServerLevel level, List<ChunkAccess> chunks) {
        var sections = new ArrayList<Section>();
        var light = level.getChunkSource().getLightEngine();
        var block = light.getLayerListener(LightLayer.BLOCK);
        var sky = light.getLayerListener(LightLayer.SKY);
        for (var chunk : chunks) {
            // #if MC_1211
            int y = chunk.getMinSection();
            // #else
            int y = chunk.getMinSectionY();
            // #endif
            for (var section : chunk.getSections()) {
                var pos = SectionPos.of(chunk.getPos(), y);
                DataLayer b = block.getDataLayerData(pos), s = sky.getDataLayerData(pos);
                int[] inherited = null;
                // Sky engines omit uniform layers; read their inherited values.
                if (s == null && level.dimensionType().hasSkyLight()) {
                    inherited = new int[256];
                    var position = new BlockPos.MutableBlockPos();
                    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                        position.set(chunk.getPos().getMinBlockX() + x, y * 16, chunk.getPos().getMinBlockZ() + z);
                        inherited[x | z << 4] = sky.getLightValue(position);
                    }
                } else if (s != null) s = s.copy();
                // #if MC_1211
                var biomes = section.getBiomes().recreate();
                for (int x = 0; x < 4; x++) for (int by = 0; by < 4; by++) for (int z = 0; z < 4; z++)
                    biomes.set(x, by, z, section.getBiomes().get(x, by, z));
                var copy = new LevelChunkSection(section.getStates().copy(), biomes);
                // #else
                var copy = section.copy();
                // #endif
                sections.add(new Section(dev.lodgen.minecraft.PersistenceRegistry.x(chunk.getPos()), y++,
                        dev.lodgen.minecraft.PersistenceRegistry.z(chunk.getPos()), copy, b == null ? null : b.copy(), s, inherited));
            }
        }
        return sections;
    }

    /** Synchronous insertion gives a real completion boundary; Voxy's async
     * enqueueIngest only acknowledges queueing and cannot report conversion failure.
     */
    public void ingest(Object engine, List<Section> sections) throws ReflectiveOperationException {
        Method acquire = method(engine.getClass(), "acquireRef", 0), release = method(engine.getClass(), "releaseRef", 0);
        acquire.invoke(engine);
        try {
            Object mapper = method(engine.getClass(), "getMapper", 0).invoke(engine);
            Object data = method(voxel, "createEmpty", 0).invoke(null);
            Method position = method(voxel, "setPosition", 3);
            for (var section : sections) {
                position.invoke(data, section.x, section.y, section.z);
                Object supplier;
                try { supplier = lightFactory.invokeExact(section); }
                catch (Throwable error) { throw new IllegalStateException("Cannot create Voxy lighting supplier", error); }
                convert.invoke(null, data, mapper, section.data.getStates(), section.data.getBiomes(), supplier);
                mip.invoke(null, data, mapper);
                insert.invoke(null, engine, data);
            }
        } finally { release.invoke(engine); }
    }

    private static Class<?> type(String name) {
        try { return Class.forName("me.cortex.voxy." + name); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException("Unsupported Voxy API: " + name, error); }
    }
    private static byte light(Section section, int x, int y, int z) {
        int b = section.block == null ? 0 : section.block.get(x, y, z);
        int s = section.sky != null ? section.sky.get(x, y, z) : section.inheritedSky == null ? 0 : section.inheritedSky[x | z << 4];
        return (byte) (s | b << 4);
    }
    /** Millions of light lookups per batch: use a typed lambda, avoiding a
     * reflective proxy and its boxed arguments for every block.
     */
    private static MethodHandle lightingFactory(Class<?> lighting) {
        try {
            var lookup = MethodHandles.lookup();
            var signature = MethodType.methodType(byte.class, int.class, int.class, int.class);
            var implementation = lookup.findStatic(VoxyBridge.class, "light", signature.insertParameterTypes(0, Section.class));
            return LambdaMetafactory.metafactory(lookup, "supply", MethodType.methodType(lighting, Section.class),
                    signature, implementation, signature).getTarget().asType(MethodType.methodType(Object.class, Section.class));
        } catch (Throwable error) { throw new IllegalStateException("Unsupported Voxy lighting API", error); }
    }
    private static Method method(Class<?> owner, String name, int count) {
        for (Method method : owner.getMethods()) if (method.getName().equals(name) && method.getParameterCount() == count) return method;
        throw new IllegalStateException("Unsupported Voxy API: " + owner.getName() + "." + name);
    }
}
