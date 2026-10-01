package dev.dhc2me.mixin;

import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue$TaskDistancePair", remap = false)
public interface DhTaskDistanceAccess {
    @Accessor("task") DataSourceRetrievalTask dhc2me$task();
    @Accessor("dist") int dhc2me$distance();
}
