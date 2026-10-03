package dev.lodgen.integration;

import dev.lodgen.generation.CaveMode;
import dev.lodgen.generation.GenerationArea;
import dev.lodgen.minecraft.ChunkGenerationPipeline;
import dev.lodgen.minecraft.LodGenerationWorld;
import dev.lodgen.minecraft.PersistenceRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasureStructure;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Three modes at identical coordinates, followed by ordinary player generation
 * and a mixed saved/LOD request. No window or persistent test world is needed.
 */
public final class CaveModeCheck {
    private static final int X = Integer.getInteger("lodgen.test.caveX", 6144), Z = Integer.getInteger("lodgen.test.caveZ", -6144);
    private static final Map<Long, Snapshot> FILL_SURFACE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Long, Snapshot> EMPTY_SURFACE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<Long, Snapshot> NORMAL_SURFACE = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, java.util.concurrent.atomic.LongAdder> FEATURES = new java.util.concurrent.ConcurrentHashMap<>();
    public static void feature(boolean skipped, Object feature, Object world, Object origin) {
        var level = (net.minecraft.world.level.WorldGenLevel) world;
        var mode = LodGenerationWorld.mode(level.getLevel());
        String key = mode + ":" + feature.getClass().getSimpleName() + ":" + skipped;
        FEATURES.computeIfAbsent(key, ignored -> new java.util.concurrent.atomic.LongAdder()).increment();
    }
    /** Inserted into the packaged fixture only, after native surface rules and
     * before decoration. Cave/ore changes may change feature random draws later.
     */
    public static void surface(Object generator, Object nativeChunk) {
        var mode = LodGenerationWorld.mode((net.minecraft.world.level.chunk.ChunkGenerator) generator);
        var chunk = (ChunkAccess) nativeChunk;
        int x = PersistenceRegistry.x(chunk.getPos()), z = PersistenceRegistry.z(chunk.getPos());
        if (x < X || x >= X + 8 || z < Z || z >= Z + 8) return;
        (mode == CaveMode.FILL ? FILL_SURFACE : mode == CaveMode.EMPTY ? EMPTY_SURFACE : NORMAL_SURFACE)
                .put(pack(x, z), new Snapshot(chunk, mode, true));
    }
    public static void run(MinecraftServer server) {
        ServerLevel original = server.overworld();
        ExecutorService workers = Executors.newFixedThreadPool(4);
        var filled = new HashMap<Long, Snapshot>();
        var empty = new HashMap<Long, Snapshot>();
        var normal = new HashMap<Long, Snapshot>();
        try {
            ServerLevel fillLevel = LodGenerationWorld.get(original, CaveMode.FILL);
            ServerLevel emptyLevel = LodGenerationWorld.get(original, CaveMode.EMPTY);
            require(fillLevel != original && emptyLevel != original && fillLevel != emptyLevel, "Modes need separate native holders");
            // Structure queries may clamp the generator to a shorter build range.
            // Ocean restoration must never write outside the returned column.
            for (var height : new net.minecraft.world.level.LevelHeightAccessor[]{
                    net.minecraft.world.level.LevelHeightAccessor.create(64, 64), net.minecraft.world.level.LevelHeightAccessor.create(-32, 16)})
                emptyLevel.getChunkSource().getGenerator().getBaseColumn(X * 16, Z * 16, height, emptyLevel.getChunkSource().randomState());
            require(PersistenceRegistry.get(fillLevel).suppress(0, 0) && PersistenceRegistry.get(emptyLevel).suppress(0, 0), "All temporary terrain must be protected");
            generate(original, CaveMode.FILL, filled, workers)
                    .thenCompose(ignored -> generate(original, CaveMode.EMPTY, empty, workers))
                    .thenCompose(ignored -> generate(original, CaveMode.GENERATE, normal, workers))
                    .thenCompose(ignored -> cachedModeChange(original, workers))
                    .thenRun(() -> compare(filled, empty, normal))
                    .thenCompose(ignored -> mixed(original, workers))
                    // Queue a new server task: a synchronous save inside the
                    // C2ME FULL-completion callback would wait on itself.
                    .whenCompleteAsync((ignored, error) -> server.execute(() -> {
                        try {
                            if (error != null) throw new IllegalStateException("Cave mode validation failed", error);
                            // A later player request obtains original terrain, never the
                            // temporary filled/hollow chunk at these same coordinates.
                            var player = original.getChunk(X, Z);
                            require(player.getBlockState(new BlockPos(X * 16, -60, Z * 16)).isAir() ==
                                    (normal.get(pack(X, Z)).state(0, -60, 0) == Block.getId(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())), "Player terrain changed after LOD generation");
                            original.getChunkSource().save(true);
                            Files.writeString(Path.of("integration-result.txt"), "PASS: Fill/Empty skip cave formulas and carvers; Fill/Empty surface layers match; normal caves remain; mixed saved targets use original generation; temporary terrain cannot be promoted or saved.\n");
                        } catch (Throwable failure) {
                            dev.lodgen.LodgenConfig.LOGGER.error("Cave mode test failed", failure);
                            try { Files.writeString(Path.of("integration-result.txt"), "FAIL: " + failure + "\n"); } catch (Exception ignored2) {}
                        } finally { workers.shutdown(); server.halt(false); }
                    }), workers);
        } catch (Throwable error) {
            dev.lodgen.LodgenConfig.LOGGER.error("Cave mode initialization failed", error);
            try { Files.writeString(Path.of("integration-result.txt"), "FAIL: " + error + "\n"); } catch (Exception ignored) {}
            workers.shutdown(); server.halt(false);
        }
    }
    private static CompletableFuture<Void> generate(ServerLevel level, CaveMode mode, Map<Long, Snapshot> output, ExecutorService workers) {
        var pipeline = new ChunkGenerationPipeline(level, mode);
        return pipeline.generate(X, Z, 8, 8, new GenerationArea(X * 16, Z * 16, 8, 0), workers, batch -> {
            require(LodGenerationWorld.mode(batch.sourceLevel()) == mode, "Request used the wrong native generation world");
            for (var chunk : batch.chunks) output.put(pack(PersistenceRegistry.x(chunk.getPos()), PersistenceRegistry.z(chunk.getPos())), new Snapshot(chunk, mode));
            return CompletableFuture.completedFuture(null);
        }).whenComplete((ignored, error) -> pipeline.close());
    }
    private static CompletableFuture<Void> mixed(ServerLevel level, ExecutorService workers) {
        var pipeline = new ChunkGenerationPipeline(level, CaveMode.EMPTY);
        var area = new GenerationArea(8192 * 16, -8192 * 16, 2, 1);
        var saved = new java.util.concurrent.atomic.AtomicInteger();
        var lod = new java.util.concurrent.atomic.AtomicInteger();
        return pipeline.generate(8190, -8194, 4, 4, area, workers, batch -> {
            for (var chunk : batch.chunks) {
                boolean saves = area.saves(PersistenceRegistry.x(chunk.getPos()), PersistenceRegistry.z(chunk.getPos()));
                require((batch.sourceLevel() == level) == saves, "Saved targets must always generate normally");
                if (saves) saved.incrementAndGet(); else lod.incrementAndGet();
            }
            return CompletableFuture.completedFuture(null);
        }).thenRun(() -> require(saved.get() == 4 && lod.get() == 12, "Mixed request lost native targets"))
                .whenComplete((ignored, error) -> pipeline.close());
    }
    private static CompletableFuture<Void> cachedModeChange(ServerLevel level, ExecutorService workers) {
        var pipeline = new ChunkGenerationPipeline(level, CaveMode.FILL);
        return pipeline.generate(X, Z, 1, 1, new GenerationArea(X * 16, Z * 16, 8, 0), workers, batch -> {
            require(LodGenerationWorld.mode(batch.sourceLevel()) == CaveMode.FILL,
                    "Generate's cached LOD-only holders blocked a cave-mode change");
            return CompletableFuture.completedFuture(null);
        }).whenComplete((ignored, error) -> pipeline.close());
    }
    private static void compare(Map<Long, Snapshot> filled, Map<Long, Snapshot> empty, Map<Long, Snapshot> normal) {
        require(filled.size() == 64 && empty.size() == 64 && normal.size() == 64, "Missing comparison chunks");
        require(FILL_SURFACE.size() == 64 && EMPTY_SURFACE.size() == 64, "Native surface probes did not execute");
        long caveAir = 0, filledAir = 0, emptySolid = 0, surfaceBlocks = 0, trees = 0, emptyTrees = 0, normalTrees = 0;
        long surfaceTrees = 0, emptySurfaceTrees = 0, normalSurfaceTrees = 0;
        int air = Block.getId(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        long skipped = FEATURES.entrySet().stream().filter(entry -> entry.getKey().endsWith(":true"))
                .mapToLong(entry -> entry.getValue().sum()).sum();
        require(skipped > 0, "Deep feature placements were not skipped");
        var firstKey = pack(X + 3, Z + 3);
        var f0 = FILL_SURFACE.get(firstKey); var e0 = EMPTY_SURFACE.get(firstKey); var n0 = NORMAL_SURFACE.get(firstKey);
        dev.lodgen.LodgenConfig.LOGGER.info("CAVE SURFACE SAMPLE: Generate/Fill/Empty biome {}/{}/{}; floor {}/{}/{}; top {}/{}/{}; pre-decoration tree blocks {}/{}/{}",
                n0.biome, f0.biome, e0.biome, n0.floors[0], f0.floors[0], e0.floors[0],
                Block.stateById(n0.state(0, n0.floors[0], 0)), Block.stateById(f0.state(0, f0.floors[0], 0)), Block.stateById(e0.state(0, e0.floors[0], 0)), n0.trees, f0.trees, e0.trees);
        for (var entry : filled.entrySet()) {
            var f = entry.getValue(); var e = empty.get(entry.getKey()); var n = normal.get(entry.getKey());
            var fsurface = FILL_SURFACE.get(entry.getKey()); var esurface = EMPTY_SURFACE.get(entry.getKey());
            trees += f.trees;
            emptyTrees += e.trees;
            normalTrees += n.trees;
            surfaceTrees += f.surfaceTrees;
            emptySurfaceTrees += e.surfaceTrees;
            normalSurfaceTrees += n.surfaceTrees;
            int cx = (int) (long) entry.getKey(), cz = (int) (entry.getKey() >> 32);
            // Feature writes can originate in neighboring chunks. Compare the
            // fully decorated interior, away from the request frontier.
            boolean interior = cx >= X + 2 && cx < X + 6 && cz >= Z + 2 && cz < Z + 6;
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                int column = x | z << 4;
                if (interior) require(fsurface.heights[column] == esurface.heights[column], "Empty changed the surface height at " + entry.getKey() + " / " + x + "," + z);
                if (interior) require(fsurface.floors[column] == esurface.floors[column], "Empty changed the ocean floor at " + entry.getKey() + " / " + x + "," + z);
                int top = f.heights[column];
                for (int y = Math.max(f.minY, fsurface.floors[column] - 16); interior && y <= fsurface.heights[column]; y++) {
                    var fs = Block.stateById(fsurface.state(x, y, z)); var es = Block.stateById(esurface.state(x, y, z));
                    require(fs == es, "Empty changed a retained surface block before decoration at " + entry.getKey() + " / " + x + "," + y + "," + z
                            + ": " + fs + " -> " + es);
                    surfaceBlocks++;
                }
                for (int y = f.minY + 8; y < Math.min(0, top - 64); y++) {
                    if (n.state(x, y, z) == air) caveAir++;
                    if (f.state(x, y, z) == air) filledAir++;
                    if (e.state(x, y, z) != air) emptySolid++;
                }
                for (int y = e.minY; y < e.minY + 8; y++)
                    require(e.state(x, y, z) == air, "Empty's virtual palette markers escaped into physical terrain");
            }
        }
        require(caveAir > 1000, "Generate did not contain caves for this seed");
        require(filledAir == 0, "Fill still contains deep caves: " + filledAir);
        require(emptySolid == 0, "Empty generated deep interior blocks: " + emptySolid);
        require(trees > 0 && emptyTrees > 0, "Surface trees disappeared");
        dev.lodgen.LodgenConfig.LOGGER.info("CAVE TREE CHECK: total Generate/Fill/Empty {}/{}/{}; surface-only {}/{}/{}",
                normalTrees, trees, emptyTrees, normalSurfaceTrees, surfaceTrees, emptySurfaceTrees);
        require(surfaceTrees >= normalSurfaceTrees * 0.75 && emptySurfaceTrees >= normalSurfaceTrees * 0.75,
                "Surface vegetation was filtered out: Generate/Fill/Empty " + normalSurfaceTrees + "/" + surfaceTrees + "/" + emptySurfaceTrees);
        dev.lodgen.LodgenConfig.LOGGER.info("CAVE CHECK: {} retained surface blocks match before decoration; Generate/Fill/Empty tree blocks {}/{}/{}; {} normal cave air blocks, {} Fill air and {} Empty solid interior blocks", surfaceBlocks, normalTrees, trees, emptyTrees, caveAir, filledAir, emptySolid);
    }
    private static long pack(int x, int z) { return (x & 0xffffffffL) | (long) z << 32; }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class Snapshot {
        final int minY, height;
        final int[] blocks, heights = new int[256], floors = new int[256];
        final String biome;
        int trees, surfaceTrees;
        Snapshot(ChunkAccess chunk, CaveMode mode) { this(chunk, mode, false); }
        Snapshot(ChunkAccess chunk, CaveMode mode, boolean surface) {
            minY = PersistenceRegistry.minY(chunk);
            height = chunk.getHeight(); blocks = new int[height * 256];
            biome = chunk.getNoiseBiome(chunk.getPos().getMinBlockX() >> 2,
                    chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, 0, 0) >> 2, chunk.getPos().getMinBlockZ() >> 2).unwrapKey().toString();
            var pos = new BlockPos.MutableBlockPos();
            for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
                heights[x | z << 4] = chunk.getHeight(surface ? Heightmap.Types.WORLD_SURFACE_WG : Heightmap.Types.WORLD_SURFACE, x, z);
                floors[x | z << 4] = chunk.getHeight(surface ? Heightmap.Types.OCEAN_FLOOR_WG : Heightmap.Types.OCEAN_FLOOR, x, z);
                for (int y = minY; y < minY + height; y++) {
                    pos.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                    var state = chunk.getBlockState(pos);
                    blocks[(y - minY) * 256 + (x | z << 4)] = Block.getId(state);
                    if (state.is(net.minecraft.tags.BlockTags.LOGS) || state.is(net.minecraft.tags.BlockTags.LEAVES)) {
                        trees++;
                        if (y >= floors[x | z << 4] - 16) surfaceTrees++;
                    }
                }
            }
            if (mode != CaveMode.GENERATE) for (var start : chunk.getAllStarts().entrySet()) {
                var step = start.getKey().step();
                require(start.getKey() instanceof BuriedTreasureStructure || step != GenerationStep.Decoration.UNDERGROUND_STRUCTURES
                        && step != GenerationStep.Decoration.UNDERGROUND_DECORATION, "Underground structure was generated");
            }
        }
        int state(int x, int y, int z) { return blocks[(y - minY) * 256 + (x | z << 4)]; }
    }
}
