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
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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
    @Unique private final dev.lodgen.generation.SpatialTaskIndex<Long, DataSourceRetrievalTask> lodgen$index = new dev.lodgen.generation.SpatialTaskIndex<>();

    @Inject(method = "close", at = @At("HEAD"))
    private void lodgen$clearIndex(CallbackInfo callback) { lodgen$index.clear(); }

    @Inject(method = "isGeneratorBusy", at = @At("HEAD"), cancellable = true)
    private void lodgen$backpressure(CallbackInfoReturnable<Boolean> callback) {
        if (LodgenConfig.INSTANCE.enabled()
                && generator instanceof GenerationAdmission admission
                && Config.Common.WorldGenerator.chunkGeneratorMode.get() == EDhApiDistantGeneratorMode.FEATURES
                && Config.Common.WorldGenerator.generatorPlan.get().chunkGenEnabled) {
            callback.setReturnValue(com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil.getWorldGenExecutor() == null
                    || admission.lodgen$isBusy() || dev.lodgen.minecraft.GenerationTasks.overridesAutomatic(level.getServerLevelWrapper().getWrappedMcObject()));
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
        // An explicit override also bounds dedicated-server DH requests. Without
        // one, only the integrated server follows the local graphics setting.
        var resolvedCenter = dev.lodgen.minecraft.GenerationCenters.resolve((net.minecraft.server.level.ServerLevel) level.getServerLevelWrapper().getWrappedMcObject(), targetPos.x, targetPos.z);
        int radius = settings.generationRadius(Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get(),
                PersistenceRegistry.isIntegratedServer(level.getServerLevelWrapper().getWrappedMcObject()));
        Function<Map.Entry<Long, DataSourceRetrievalTask>, Object> filtered = entry -> {
            var task = entry.getValue();
            if (radius > 0 && !GenerationBounds.retain(tasks, entry.getKey(), task, task.future,
                    GenerationBounds.overlaps(DhSectionPos.getMinCornerBlockX(task.pos),
                    DhSectionPos.getMinCornerBlockZ(task.pos), DhSectionPos.getBlockWidth(task.pos), resolvedCenter.getX(), resolvedCenter.getZ(), radius))) {
                return null;
            }
            return transform.apply(entry);
        };
        var context = java.util.List.of(targetPos.x >> 4, targetPos.z >> 4, resolvedCenter.getX() >> 4, resolvedCenter.getZ() >> 4, radius);
        return lodgen$index.select(tasks, context, entry -> {
            Object pair = filtered.apply(entry);
            if (pair == null) return null;
            var access = (DhTaskDistanceAccess) pair;
            var center = DhSectionPos.getCenterBlockPos(access.lodgen$task().pos);
            return new SpatialGenerationOrder.Ranked<>(pair,
                    SpatialGenerationOrder.priority(access.lodgen$distance(), center.x, center.z));
        });
    }
}
