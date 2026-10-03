package dev.lodgen.mixin;

import dev.lodgen.minecraft.LodGenerationWorld;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.structures.BuriedTreasureStructure;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkGenerator.class)
public abstract class CaveStructureMixin {
    @Inject(method = "tryGenerateStructure", at = @At("HEAD"), cancellable = true)
    private void lodgen$surfaceStructures(StructureSet.StructureSelectionEntry entry, StructureManager structures,
            RegistryAccess registry, RandomState random, StructureTemplateManager templates, long seed,
            ChunkAccess chunk, ChunkPos position,
            // #if MC_263
            ResourceKey<Level> dimension, net.minecraft.world.level.biome.Climate.Sampler sampler,
            // #else
            // #if MC_1211
            net.minecraft.core.SectionPos section,
            // #else
            net.minecraft.core.SectionPos section, ResourceKey<Level> dimension,
            // #endif
            // #endif
            CallbackInfoReturnable<Boolean> callback) {
        if (!LodGenerationWorld.simplified((ChunkGenerator) (Object) this)) return;
        var structure = entry.structure().value();
        var step = structure.step();
        if (!(structure instanceof BuriedTreasureStructure)
                && (step == GenerationStep.Decoration.UNDERGROUND_STRUCTURES || step == GenerationStep.Decoration.UNDERGROUND_DECORATION))
            callback.setReturnValue(false);
    }
}
