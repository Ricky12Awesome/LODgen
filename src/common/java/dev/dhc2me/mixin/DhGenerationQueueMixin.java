package dev.dhc2me.mixin;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import dev.dhc2me.DhC2meConfig;
import dev.dhc2me.generation.SpatialGenerationOrder;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Select coherent terrain patches instead of scattered equal-distance tasks.
 * Only the builtin FEATURES queue changes; API overrides retain their ordering.
 */
@Mixin(targets = "com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue", remap = false)
public abstract class DhGenerationQueueMixin {
    @Shadow @Final private IDhApiWorldGenerator generator;

    @Redirect(method = "tryStartNextWorldGenTask", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ConcurrentHashMap;reduceEntries(JLjava/util/function/Function;Ljava/util/function/BiFunction;)Ljava/lang/Object;"))
    private Object dhc2me$selectNearby(ConcurrentHashMap<Long, DataSourceRetrievalTask> tasks, long threshold,
                                      Function<Map.Entry<Long, DataSourceRetrievalTask>, Object> transform,
                                      BiFunction<Object, Object, Object> reduce) {
        if (!DhC2meConfig.INSTANCE.enabled() || !DhC2meConfig.INSTANCE.spatialBatching()
                || !(generator instanceof DhWorldGenerator)
                || Config.Common.WorldGenerator.chunkGeneratorMode.get() != EDhApiDistantGeneratorMode.FEATURES
                || !Config.Common.WorldGenerator.generatorPlan.get().chunkGenEnabled) {
            return tasks.reduceEntries(threshold, transform, reduce);
        }
        SpatialGenerationOrder.Ranked<Object> selected = tasks.reduceEntries(threshold, entry -> {
            Object pair = transform.apply(entry);
            if (pair == null) return null;
            var access = (DhTaskDistanceAccess) pair;
            var center = DhSectionPos.getCenterBlockPos(access.dhc2me$task().pos);
            return new SpatialGenerationOrder.Ranked<>(pair,
                    SpatialGenerationOrder.priority(access.dhc2me$distance(), center.x, center.z));
        }, (a, b) -> a.priority() <= b.priority() ? a : b);
        return selected == null ? null : selected.value();
    }
}
