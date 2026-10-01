package dev.lodgen.mixin;

import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue$TaskDistancePair", remap = false)
public interface DhTaskDistanceAccess {
    @Accessor("task") DataSourceRetrievalTask lodgen$task();
    @Accessor("dist") int lodgen$distance();
}
