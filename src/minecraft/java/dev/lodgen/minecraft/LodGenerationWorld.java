package dev.lodgen.minecraft;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.CaveMode;
import dev.lodgen.mixin.ServerExecutorAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
// #if MC_1211
import net.minecraft.resources.ResourceLocation;
// #else
import net.minecraft.resources.Identifier;
// #endif
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
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
    private static final Map<ChunkGenerator, net.minecraft.world.level.block.state.BlockState> SURFACE_FLUIDS = new ConcurrentHashMap<>();
    private static final Map<NoiseGeneratorSettings, Boolean> EMPTY_SETTINGS = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    private static final java.util.Set<MinecraftServer> STOPPING = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private static int serial;

    public static boolean constructing() { return CONSTRUCTING.get(); }
    public static CaveMode mode(ServerLevel level) { return MODES.getOrDefault(level, CaveMode.GENERATE); }
    public static boolean simplified(ChunkGenerator generator) { return GENERATORS.containsKey(generator); }
    public static CaveMode mode(ChunkGenerator generator) { return GENERATORS.getOrDefault(generator, CaveMode.GENERATE); }
    public static net.minecraft.world.level.block.state.BlockState surfaceFluid(ChunkGenerator generator) { return SURFACE_FLUIDS.get(generator); }
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
        var settings = withoutCaves(original, noise.generatorSettings().value(), mode);
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

    private static NoiseGeneratorSettings withoutCaves(ServerLevel level, NoiseGeneratorSettings settings, CaveMode mode) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, level.registryAccess());
        var encoded = NoiseGeneratorSettings.DIRECT_CODEC.encodeStart(ops, settings).getOrThrow().getAsJsonObject();
        var router = encoded.getAsJsonObject("noise_router");
        // 26.3 moved the density tree into a registered holder. Encode its value
        // so the same conservative transformation can see the actual formula.
        // #if MC_263
        if (router.get("final_density").isJsonPrimitive()) {
            var key = ResourceKey.create(Registries.DENSITY_FUNCTION, Identifier.parse(router.get("final_density").getAsString()));
            router.add("final_density", net.minecraft.world.level.levelgen.densityfunction.DensityFunction.CODEC
                    .encodeStart(ops, level.registryAccess().lookupOrThrow(Registries.DENSITY_FUNCTION).getOrThrow(key).value()).getOrThrow());
        }
        // #endif
        int[] replacements = {0};
        JsonElement[] terrain = {null};
        router.add("final_density", stripCaves(router.get("final_density"), replacements, terrain));
        if (terrain[0] == null || router.get("final_density").toString().contains("/caves/")) return null;
        if (encoded.has("ore_veins_enabled")) encoded.addProperty("ore_veins_enabled", false);
        if (encoded.has("aquifers_enabled")) encoded.addProperty("aquifers_enabled", false);
        // #if MC_263
        encoded.remove("aquifers");
        // #endif
        if (mode == CaveMode.EMPTY) {
            int[] masked = {0};
            router.add("final_density", emptyShell(router.get("final_density"), terrain[0], masked));
            if (masked[0] == 0) return null;
            encoded.add("default_fluid", net.minecraft.world.level.block.state.BlockState.CODEC
                    .encodeStart(ops, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState()).getOrThrow());
        }
        return NoiseGeneratorSettings.DIRECT_CODEC.parse(ops, encoded).getOrThrow();
    }

    private static JsonElement emptyShell(JsonElement value, JsonElement terrain, int[] masked) {
        if (!value.isJsonObject()) return value;
        var object = value.getAsJsonObject();
        var result = new JsonObject();
        boolean interpolate = object.has("type") && object.get("type").getAsString().equals("minecraft:interpolated");
        for (var entry : object.entrySet()) {
            if (interpolate && (entry.getKey().equals("argument") || entry.getKey().equals("input"))) {
                // Keep the cutoff inside native interpolation. Sampling the
                // 3D terrain noise at every voxel would erase the speed gain.
                // Follow terrain density so ocean floors and overhangs remain.
                var shell = new JsonObject();
                shell.addProperty("type", "minecraft:range_choice");
                shell.add("input", terrain);
                shell.addProperty("min_inclusive", 4.0);
                shell.addProperty("max_exclusive", 1000000.0);
                shell.addProperty("when_in_range", -1.0);
                shell.add("when_out_of_range", entry.getValue());
                result.add(entry.getKey(), shell); masked[0]++;
            } else result.add(entry.getKey(), emptyShell(entry.getValue(), terrain, masked));
        }
        return result;
    }

    /** Retain the original terrain, jaggedness, interpolation and top/bottom
     * slides; remove only the vanilla cave selector and the noodle minimum.
     */
    private static JsonElement stripCaves(JsonElement value, int[] replacements, JsonElement[] terrain) {
        if (!value.isJsonObject()) return value;
        JsonObject object = value.getAsJsonObject();
        String type = object.has("type") ? object.get("type").getAsString() : "";
        if (type.equals("minecraft:range_choice") && object.has("input") && reference(object.get("input"), "sloped_cheese")) {
            replacements[0]++;
            terrain[0] = object.get("input");
            return object.get("input");
        }
        String left = object.has("left") ? "left" : "argument1", right = object.has("right") ? "right" : "argument2";
        if (type.equals("minecraft:min") && object.has(right) && reference(object.get(right), "caves/noodle")) {
            replacements[0]++;
            return stripCaves(object.get(left), replacements, terrain);
        }
        var result = new JsonObject();
        for (var entry : object.entrySet()) result.add(entry.getKey(), stripCaves(entry.getValue(), replacements, terrain));
        return result;
    }
    private static boolean reference(JsonElement value, String name) {
        // 26.2 caches the terrain selector's input. Retain that wrapper in the
        // transformed graph, while recognizing the reference it contains.
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("type") && object.get("type").getAsString().equals("minecraft:cache_once")) {
                var argument = object.get("argument");
                if (argument == null) argument = object.get("input");
                return argument != null && reference(argument, name);
            }
        }
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && (value.getAsString().equals("minecraft:overworld/" + name)
                    || value.getAsString().equals("minecraft:overworld_large_biomes/" + name)
                    || value.getAsString().equals("minecraft:overworld_amplified/" + name));
    }

    public static boolean normalTerrain(ServerLevel level, int x, int z) {
        var policy = PersistenceRegistry.get(level);
        if (policy != null && policy.permanent(x, z)
                || level.getChunkSource().hasChunk(x, z) && (policy == null || !policy.suppress(x, z))) return true;
        Path root = level.getServer().getWorldPath(LevelResource.ROOT);
        // #if MC_1211
        if (level.dimension() == Level.NETHER) root = root.resolve("DIM-1");
        else if (level.dimension() == Level.END) root = root.resolve("DIM1");
        else if (level.dimension() != Level.OVERWORLD) {
            // #if MC_1211
            var id = level.dimension().location();
            // #else
            var id = level.dimension().identifier();
            // #endif
            root = root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
        }
        // #else
        var id = level.dimension().identifier();
        root = root.resolve("dimensions").resolve(id.getNamespace()).resolve(id.getPath());
        // #endif
        // Conservatively use normal generation for any existing saved region,
        // including unloaded player builds. Never replace those with seed terrain.
        return Files.exists(root.resolve("region").resolve("r." + (x >> 5) + "." + (z >> 5) + ".mca"));
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
