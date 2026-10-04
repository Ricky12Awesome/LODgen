package dev.lodgen.minecraft;

import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.CaveMode;
import dev.lodgen.mixin.ServerExecutorAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
// #if MC_1211
import net.minecraft.resources.ResourceLocation;
// #else
import net.minecraft.resources.Identifier;
// #endif
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Native C2ME worlds with separate holders, lighting and temporary storage.
 * They never enter the server's dimension list or a player's save directory.
 */
public final class LodGenerationWorld {
    private static final ThreadLocal<Boolean> CONSTRUCTING = ThreadLocal.withInitial(() -> false);
    private static final Map<ServerLevel, EnumMap<CaveMode, Session>> WORLDS = new IdentityHashMap<>();
    private static final Map<ServerLevel, CaveMode> MODES = new ConcurrentHashMap<>();
    private static final Map<ChunkGenerator, CaveMode> GENERATORS = new ConcurrentHashMap<>();
    private static final Map<ChunkGenerator, BlockState> SURFACE_FLUIDS = new ConcurrentHashMap<>();
    private static final Map<NoiseGeneratorSettings, Boolean> EMPTY_SETTINGS = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private static final java.util.Set<MinecraftServer> STOPPING = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static int serial;

    public static boolean constructing() { return CONSTRUCTING.get(); }
    public static CaveMode mode(ServerLevel level) { return MODES.getOrDefault(level, CaveMode.GENERATE); }
    public static boolean simplified(ChunkGenerator generator) { return GENERATORS.containsKey(generator); }
    public static CaveMode mode(ChunkGenerator generator) { return GENERATORS.getOrDefault(generator, CaveMode.GENERATE); }
    public static BlockState surfaceFluid(ChunkGenerator generator) { return SURFACE_FLUIDS.get(generator); }
    public static boolean empty(NoiseGeneratorSettings settings) { return EMPTY_SETTINGS.containsKey(settings); }

    /** Called on the server thread, before submitting any native requests. */
    public static ServerLevel get(ServerLevel original, CaveMode mode) {
        if (mode == CaveMode.GENERATE) return original;
        if (STOPPING.contains(original.getServer())) throw new java.util.concurrent.CancellationException("LOD generation is closing");
        var modes = WORLDS.computeIfAbsent(original, ignored -> new EnumMap<>(CaveMode.class));
        var session = modes.get(mode);
        if (session != null) return session.level;
        var generator = original.getChunkSource().getGenerator();
        if (!(generator instanceof NoiseBasedChunkGenerator noise) || !original.dimensionType().hasSkyLight()) {
            modes.put(mode, new Session(original, null, null));
            return original;
        }
        var settings = LodTerrainSettings.withoutCaves(original, noise.generatorSettings().value(), mode);
        // Unknown terrain formulas stay on the unmodified normal pipeline.
        if (settings == null) {
            modes.put(mode, new Session(original, null, null));
            LodgenConfig.LOGGER.warn("LOD cave mode {} is unavailable for this terrain formula in {}; using Generate", mode, original.dimension());
            return original;
        }
        if (mode == CaveMode.EMPTY) EMPTY_SETTINGS.put(settings, true);
        var isolatedGenerator = new NoiseBasedChunkGenerator(noise.getBiomeSource(), Holder.direct(settings));
        Path directory = null;
        LevelStorageSource.LevelStorageAccess storage = null;
        try {
            directory = Files.createTempDirectory("lodgen-world-");
            storage = LevelStorageSource.createDefault(directory).createAccess("terrain");
            // #if MC_1211
            var key = ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath("lodgen", "temporary_" + serial++));
            // #else
            var key = ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("lodgen", "temporary_" + serial++));
            // #endif
            var server = original.getServer();
            var data = new DerivedLevelData(server.getWorldData(), server.getWorldData().overworldData());
            var stem = new LevelStem(original.dimensionTypeRegistration(), isolatedGenerator);
            CONSTRUCTING.set(true);
            // #if MC_1211
            var level = new ServerLevel(server, ((ServerExecutorAccess) server).lodgen$executor(), storage, data,
                    key, stem, new net.minecraft.server.level.progress.ChunkProgressListener() {
                        public void updateSpawnPos(net.minecraft.world.level.ChunkPos pos) {}
                        public void onStatusChange(net.minecraft.world.level.ChunkPos pos, net.minecraft.world.level.chunk.status.ChunkStatus status) {}
                        public void start() {}
                        public void stop() {}
                    }, false, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(original.getSeed()), List.of(), false, null);
            // #else
            var level = new ServerLevel(server, ((ServerExecutorAccess) server).lodgen$executor(), storage, data,
                    key, stem, false, net.minecraft.world.level.biome.BiomeManager.obfuscateSeed(original.getSeed()), List.of(), false);
            // #endif
            MODES.put(level, mode);
            GENERATORS.put(isolatedGenerator, mode);
            SURFACE_FLUIDS.put(isolatedGenerator, noise.generatorSettings().value().defaultFluid());
            modes.put(mode, new Session(level, storage, directory));
            LodgenConfig.LOGGER.info("Isolated {} LOD terrain initialized for {}", mode, original.dimension());
            return level;
        } catch (IOException | RuntimeException error) {
            EMPTY_SETTINGS.remove(settings);
            if (storage != null) storage.safeClose();
            delete(directory);
            throw new IllegalStateException("Cannot initialize isolated LOD terrain", error);
        } finally { CONSTRUCTING.remove(); }
    }

    public static boolean normalTerrain(ServerLevel level, int x, int z) {
        var policy = PersistenceRegistry.get(level);
        if (policy != null && policy.permanent(x, z)
                || level.getChunkSource().hasChunk(x, z) && (policy == null || !policy.suppress(x, z))) return true;
        // Conservatively use normal generation for any existing saved region,
        // including unloaded player builds. Never replace those with seed terrain.
        return ChunkPersistence.regionExists(WorldPaths.dimensionDirectory(level).resolve("region"), x, z);
    }

    public static void tick(MinecraftServer server) {
        for (var entry : WORLDS.entrySet()) if (entry.getKey().getServer() == server)
            for (var session : entry.getValue().values()) if (session.storage != null)
                session.level.getChunkSource().tick(() -> true, false);
    }
    public static boolean pollTask(MinecraftServer server) {
        for (var entry : WORLDS.entrySet()) if (entry.getKey().getServer() == server)
            for (var session : entry.getValue().values()) if (session.storage != null && session.level.getChunkSource().pollTask()) return true;
        return false;
    }
    public static void close(MinecraftServer server) {
        STOPPING.add(server);
        var iterator = WORLDS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (entry.getKey().getServer() != server) continue;
            for (var session : entry.getValue().values()) if (session.storage != null) {
                try { session.level.close(); }
                catch (IOException error) { LodgenConfig.LOGGER.error("Cannot close temporary LOD terrain", error); }
                finally {
                    MODES.remove(session.level);
                    GENERATORS.remove(session.level.getChunkSource().getGenerator());
                    SURFACE_FLUIDS.remove(session.level.getChunkSource().getGenerator());
                    if (session.level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator noise)
                        EMPTY_SETTINGS.remove(noise.generatorSettings().value());
                    session.storage.safeClose(); delete(session.directory);
                }
            }
            iterator.remove();
        }
    }
    private static void delete(Path directory) {
        if (directory == null) return;
        try (var files = Files.walk(directory)) {
            for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        } catch (IOException error) { LodgenConfig.LOGGER.warn("Cannot remove temporary LOD terrain {}", directory, error); }
    }
    private record Session(ServerLevel level, LevelStorageSource.LevelStorageAccess storage, Path directory) {}
}
