package dev.lodgen.mixin;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import dev.lodgen.LodgenConfig;
import dev.lodgen.generation.GenerationBounds;
import dev.lodgen.generation.GenerationAdmission;
import dev.lodgen.generation.SpatialGenerationOrder;
import dev.lodgen.minecraft.PersistenceRegistry;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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
    @Shadow @Final private IDhServerLevel level;

    @Inject(method = "isGeneratorBusy", at = @At("RETURN"), cancellable = true)
    private void lodgen$backpressure(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue() && LodgenConfig.INSTANCE.enabled()
                && generator instanceof GenerationAdmission admission
                && Config.Common.WorldGenerator.chunkGeneratorMode.get() == EDhApiDistantGeneratorMode.FEATURES
                && Config.Common.WorldGenerator.generatorPlan.get().chunkGenEnabled
                && admission.lodgen$isBusy()) {
            callback.setReturnValue(true);
        }
    }

    @Redirect(method = "tryStartNextWorldGenTask", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ConcurrentHashMap;reduceEntries(JLjava/util/function/Function;Ljava/util/function/BiFunction;)Ljava/lang/Object;"))
    private Object lodgen$selectNearby(ConcurrentHashMap<Long, DataSourceRetrievalTask> tasks, long threshold,
                                      Function<Map.Entry<Long, DataSourceRetrievalTask>, Object> transform,
                                      BiFunction<Object, Object, Object> reduce, DhBlockPos2D targetPos) {
        var settings = LodgenConfig.INSTANCE;
        if (!settings.enabled()
                || !(generator instanceof DhWorldGenerator)
                || Config.Common.WorldGenerator.chunkGeneratorMode.get() != EDhApiDistantGeneratorMode.FEATURES
                || !Config.Common.WorldGenerator.generatorPlan.get().chunkGenEnabled) {
            return tasks.reduceEntries(threshold, transform, reduce);
        }
        // Dedicated servers have independent per-player/pregen ranges. Only the
        // integrated server should obey the local graphics setting. Cancel waiting
        // work, never pooled data currently read by native workers.
        boolean limitToRenderDistance = PersistenceRegistry.isIntegratedServer(level.getServerLevelWrapper().getWrappedMcObject());
        int radius = Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get();
        Function<Map.Entry<Long, DataSourceRetrievalTask>, Object> filtered = entry -> {
            var task = entry.getValue();
            if (limitToRenderDistance && !GenerationBounds.retain(tasks, entry.getKey(), task, task.future,
                    GenerationBounds.overlaps(DhSectionPos.getMinCornerBlockX(task.pos),
                    DhSectionPos.getMinCornerBlockZ(task.pos), DhSectionPos.getBlockWidth(task.pos), targetPos.x, targetPos.z, radius))) {
                return null;
            }
            return transform.apply(entry);
        };
        if (!settings.spatialBatching()) return tasks.reduceEntries(threshold, filtered, reduce);
        SpatialGenerationOrder.Ranked<Object> selected = tasks.reduceEntries(threshold, entry -> {
            Object pair = filtered.apply(entry);
            if (pair == null) return null;
            var access = (DhTaskDistanceAccess) pair;
            var center = DhSectionPos.getCenterBlockPos(access.lodgen$task().pos);
            return new SpatialGenerationOrder.Ranked<>(pair,
                    SpatialGenerationOrder.priority(access.lodgen$distance(), center.x, center.z));
        }, (a, b) -> a.priority() <= b.priority() ? a : b);
        return selected == null ? null : selected.value();
    }
}
