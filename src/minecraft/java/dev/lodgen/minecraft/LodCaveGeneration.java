package dev.lodgen.minecraft;

import dev.lodgen.generation.CaveMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

/** Decisions occur before noise, carving and feature placement; no generated
 * cave blocks are removed afterwards. Empty's lower density is air from birth.
 */
public final class LodCaveGeneration {
    public static boolean skipFeature(WorldGenLevel level, BlockPos position, Object feature) {
        if (LodGenerationWorld.mode(level.getLevel()) == CaveMode.GENERATE) return false;
        // Selectors can start at y=0 and move their child placements to the
        // surface. Filter the concrete children after their placement modifiers.
        if (feature instanceof net.minecraft.world.level.levelgen.feature.RandomSelectorFeature
                || feature instanceof net.minecraft.world.level.levelgen.feature.SimpleRandomSelectorFeature
                || feature instanceof net.minecraft.world.level.levelgen.feature.RandomBooleanSelectorFeature) return false;
        var chunk = level.getChunk(position.getX() >> 4, position.getZ() >> 4);
        if (feature instanceof net.minecraft.world.level.levelgen.feature.SnowAndFreezeFeature) return false;
        if (position.getY() < PersistenceRegistry.minY(chunk) + CaveMarkers.HEIGHT
                && (feature instanceof net.minecraft.world.level.levelgen.feature.DiskFeature
                || feature instanceof net.minecraft.world.level.levelgen.feature.BlockColumnFeature
                || feature instanceof net.minecraft.world.level.levelgen.feature.SimpleBlockFeature
                || feature instanceof net.minecraft.world.level.levelgen.feature.ReplaceBlobsFeature)) return false;
        var floors = CaveMarkers.data(chunk).lodgen$surfaceFloors();
        int floor = floors == null ? level.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, position.getX(), position.getZ())
                : floors[(position.getX() & 15) | (position.getZ() & 15) << 4];
        return position.getY() < floor - 16;
    }

    public static void surfaceWater(NoiseBasedChunkGenerator generator, ChunkAccess chunk, net.minecraft.world.level.levelgen.RandomState random) {
        var fluid = LodGenerationWorld.surfaceFluid(generator);
        if (fluid == null) return;
        var floors = new int[256];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++)
            floors[x | z << 4] = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR_WG, x, z) + 1;
        ((CaveSurface) chunk).lodgen$surfaceFloors(floors);
        if (!generator.generatorSettings().value().defaultFluid().isAir()) return;
        // #if MC_1211
        var key = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("minecraft", "bedrock_floor");
        // #else
        var key = net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "bedrock_floor");
        // #endif
        ((CaveSurface) chunk).lodgen$markers(new CaveMarkers(PersistenceRegistry.minY(chunk), random.getOrCreateRandomFactory(key),
                generator.generatorSettings().value().defaultBlock()));
        int sea = Math.min(generator.getSeaLevel(), PersistenceRegistry.minY(chunk) + chunk.getHeight());
        var position = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            int top = floors[x | z << 4];
            for (int y = top; y < sea; y++) {
                position.set(chunk.getPos().getMinBlockX() + x, y, chunk.getPos().getMinBlockZ() + z);
                // #if MC_1211
                chunk.setBlockState(position, fluid, false);
                // #else
                chunk.setBlockState(position, fluid, 0);
                // #endif
            }
        }
    }
}
