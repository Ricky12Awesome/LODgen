package dev.ricky12awesome.lodgen.mixin;

import com.seibel.distanthorizons.api.interfaces.override.worldGenerator.IDhApiWorldGenerator;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.generation.DhWorldGenerator;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.level.IDhServerLevel;
import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.generation.GenerationBounds;
import dev.ricky12awesome.lodgen.generation.GenerationAdmission;
import dev.ricky12awesome.lodgen.generation.SpatialGenerationOrder;
import dev.ricky12awesome.lodgen.minecraft.PersistenceRegistry;
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
    @Unique private final dev.ricky12awesome.lodgen.generation.SpatialTaskIndex<Long, DataSourceRetrievalTask> lodgen$index = new dev.ricky12awesome.lodgen.generation.SpatialTaskIndex<>();
    @Unique private final java.util.concurrent.atomic.AtomicInteger lodgen$roughPending = new java.util.concurrent.atomic.AtomicInteger();
    @Unique private final java.util.concurrent.atomic.AtomicInteger lodgen$roughActive = new java.util.concurrent.atomic.AtomicInteger();

    @Inject(method = "submitRetrievalTask", at = @At("RETURN"))
    private void lodgen$countSurfaceRequests(long pos, byte detail, CallbackInfoReturnable<java.util.concurrent.CompletableFuture<com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult>> callback) {
        var future = callback.getReturnValue();
        if (DhSectionPos.getDetailLevel(pos) <= 6 || future.isDone()) return;
        lodgen$roughPending.incrementAndGet();
        future.whenComplete((ignored, error) -> lodgen$roughPending.decrementAndGet());
    }
    @Inject(method = "startWorldGenTaskGroup", at = @At("HEAD"))
    private void lodgen$countActiveSurfaces(DataSourceRetrievalTask task, CallbackInfo callback) {
        if (DhSectionPos.getDetailLevel(task.pos) <= 6) return;
        lodgen$roughActive.incrementAndGet();
        task.future.whenComplete((ignored, error) -> lodgen$roughActive.decrementAndGet());
    }

    @Inject(method = "close", at = @At("HEAD"))
    private void lodgen$clearIndex(CallbackInfo callback) { lodgen$index.clear(); }

    @Inject(method = "isGeneratorBusy", at = @At("HEAD"), cancellable = true)
    private void lodgen$backpressure(CallbackInfoReturnable<Boolean> callback) {
        Object nativeLevel = level.getServerLevelWrapper().getWrappedMcObject();
        if (!Config.Common.WorldGenerator.generatorPlan.get().generationEnabled
                || dev.ricky12awesome.lodgen.minecraft.GenerationTasks.automaticBlocked(nativeLevel)) {
            callback.setReturnValue(true);
            return;
        }
        if (!dev.ricky12awesome.lodgen.GenerationSettings.policy().dhChunks()) return;
        boolean extraChunks = dev.ricky12awesome.lodgen.minecraft.GenerationTasks.overridesAutomatic(nativeLevel);
        // API generators (e.g. SeedGen) can also generate full-detail features.
        // The fixed-area task already supplies that chunk phase. Apply the same
        // task ownership to overrides so WWOO isn't generated twice in parallel.
        if (dev.ricky12awesome.lodgen.minecraft.GenerationTasks.commandOverrides(nativeLevel)
                || extraChunks && (!Config.Common.WorldGenerator.generatorPlan.get().surfaceGenEnabled || lodgen$roughPending.get() == 0)) {
            callback.setReturnValue(true);
            return;
        }
        if (generator instanceof GenerationAdmission admission) {
            callback.setReturnValue(com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil.getWorldGenExecutor() == null
                    || admission.lodgen$isBusy()
                    || lodgen$roughActive.get() > Math.max(1, Config.Common.MultiThreading.numberOfThreads.get()));
        }
    }

    @Redirect(method = "tryStartNextWorldGenTask", at = @At(value = "INVOKE",
            target = "Ljava/util/concurrent/ConcurrentHashMap;reduceEntries(JLjava/util/function/Function;Ljava/util/function/BiFunction;)Ljava/lang/Object;"))
    private Object lodgen$selectNearby(ConcurrentHashMap<Long, DataSourceRetrievalTask> tasks, long threshold,
                                      Function<Map.Entry<Long, DataSourceRetrievalTask>, Object> transform,
                                      BiFunction<Object, Object, Object> reduce, DhBlockPos2D targetPos) {
        var settings = LodgenConfig.INSTANCE;
        if (!(generator instanceof DhWorldGenerator) || !dev.ricky12awesome.lodgen.GenerationSettings.policy().dhChunks()) {
            return tasks.reduceEntries(threshold, transform, reduce);
        }
        // An explicit override also bounds dedicated-server DH requests. Without
        // one, only the integrated server follows the local graphics setting.
        var resolvedCenter = dev.ricky12awesome.lodgen.minecraft.GenerationCenters.resolve((net.minecraft.server.level.ServerLevel) level.getServerLevelWrapper().getWrappedMcObject(), targetPos.x, targetPos.z);
        int radius = settings.generationRadius(Config.Client.Advanced.Graphics.Quality.lodChunkRenderDistanceRadius.get(),
                PersistenceRegistry.isIntegratedServer(level.getServerLevelWrapper().getWrappedMcObject()));
        boolean extraChunks = dev.ricky12awesome.lodgen.minecraft.GenerationTasks.overridesAutomatic(level.getServerLevelWrapper().getWrappedMcObject());
        Function<Map.Entry<Long, DataSourceRetrievalTask>, Object> filtered = entry -> {
            var task = entry.getValue();
            if (DhSectionPos.getDetailLevel(task.pos) > 6) return transform.apply(entry);
            // Fixed-area jobs provide the chunk phase. DH can still request its
            // normal rough surfaces, including beyond the custom chunk radius.
            if (extraChunks) return null;
            if (radius > 0 && !GenerationBounds.retain(tasks, entry.getKey(), task, task.future,
                    GenerationBounds.overlaps(DhSectionPos.getMinCornerBlockX(task.pos),
                    DhSectionPos.getMinCornerBlockZ(task.pos), DhSectionPos.getBlockWidth(task.pos), resolvedCenter.getX(), resolvedCenter.getZ(), radius))) {
                return null;
            }
            return transform.apply(entry);
        };
        var context = java.util.List.of(targetPos.x >> 4, targetPos.z >> 4, resolvedCenter.getX() >> 4, resolvedCenter.getZ() >> 4, radius,
                Config.Common.WorldGenerator.generatorPlan.get(), extraChunks);
        return lodgen$index.select(tasks, context, entry -> {
            Object pair = filtered.apply(entry);
            if (pair == null) return null;
            var access = (DhTaskDistanceAccess) pair;
            // Leave rough-surface tasks at DH's original detail/distance
            // priority. Native ordering applies only after reaching chunks.
            if (DhSectionPos.getDetailLevel(access.lodgen$task().pos) > 6)
                return new SpatialGenerationOrder.Ranked<>(pair, Long.MIN_VALUE + Math.max(0, access.lodgen$distance()));
            var center = DhSectionPos.getCenterBlockPos(access.lodgen$task().pos);
            return new SpatialGenerationOrder.Ranked<>(pair,
                    SpatialGenerationOrder.priority(center.x, center.z, resolvedCenter.getX(), resolvedCenter.getZ()));
        });
    }
}
