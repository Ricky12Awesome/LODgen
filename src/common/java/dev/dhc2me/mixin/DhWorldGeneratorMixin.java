package dev.dhc2me.mixin;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import dev.dhc2me.DhC2meConfig;
import dev.dhc2me.FeatureGenerationService;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

@Mixin(targets = "com.seibel.distanthorizons.core.generation.DhWorldGenerator", remap = false)
public abstract class DhWorldGeneratorMixin {
    @Shadow @Final public IServerLevelWrapper serverLevelWrapper;
    @Unique private FeatureGenerationService dhc2me$generator;
    @Unique private boolean dhc2me$closed;

    @Inject(method = "generateLod", at = @At("HEAD"), cancellable = true)
    private synchronized void dhc2me$generateFeatures(int minX, int minZ, int posX, int posZ, byte detail,
                                                     IDhApiFullDataSource data, EDhApiDistantGeneratorMode mode,
                                                     ExecutorService executor, Consumer<IDhApiFullDataSource> consumer,
                                                     CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        if (!DhC2meConfig.INSTANCE.enabled() || mode != EDhApiDistantGeneratorMode.FEATURES || detail != 0
                || !Config.Common.WorldGenerator.generatorPlan.get().chunkGenEnabled) return;
        if (dhc2me$closed) {
            callback.setReturnValue(CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Generator closed")));
            return;
        }
        try {
            if (dhc2me$generator == null) dhc2me$generator = new FeatureGenerationService(serverLevelWrapper);
            callback.setReturnValue(dhc2me$generator.generate(minX, minZ, data, executor, consumer));
        } catch (RuntimeException | LinkageError error) {
            DhC2meConfig.LOGGER.error("Cannot start FEATURES generation through the normal chunk pipeline", error);
            callback.setReturnValue(CompletableFuture.failedFuture(error));
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private synchronized void dhc2me$close(CallbackInfo callback) {
        dhc2me$closed = true;
        if (dhc2me$generator != null) dhc2me$generator.close();
    }
}
