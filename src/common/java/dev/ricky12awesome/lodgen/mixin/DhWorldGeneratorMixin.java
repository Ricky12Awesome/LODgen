package dev.ricky12awesome.lodgen.mixin;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import com.seibel.distanthorizons.api.objects.data.IDhApiFullDataSource;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.FeatureGenerationService;
import dev.ricky12awesome.lodgen.generation.GenerationAdmission;
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
public abstract class DhWorldGeneratorMixin implements GenerationAdmission {
    @Shadow @Final public IServerLevelWrapper serverLevelWrapper;
    @Unique private FeatureGenerationService lodgen$generator;
    @Unique private boolean lodgen$closed;

    @Override public synchronized boolean lodgen$isBusy() {
        return lodgen$closed || (lodgen$generator != null && lodgen$generator.isBusy());
    }

    @Inject(method = "generateLod", at = @At("HEAD"), cancellable = true)
    private synchronized void lodgen$generateFeatures(int minX, int minZ, int posX, int posZ, byte detail,
                                                     IDhApiFullDataSource data, EDhApiDistantGeneratorMode mode,
                                                     ExecutorService executor, Consumer<IDhApiFullDataSource> consumer,
                                                     CallbackInfoReturnable<CompletableFuture<Void>> callback) {
        if (!Config.Common.WorldGenerator.generatorPlan.get().generationEnabled) {
            callback.setReturnValue(CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("DH generation disabled")));
            return;
        }
        if (mode != EDhApiDistantGeneratorMode.FEATURES || detail != 0
                || !Config.Common.WorldGenerator.generatorPlan.get().chunkGenEnabled) return;
        if (lodgen$closed) {
            callback.setReturnValue(CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("Generator closed")));
            return;
        }
        try {
            if (lodgen$generator == null) lodgen$generator = new FeatureGenerationService(serverLevelWrapper);
            callback.setReturnValue(lodgen$generator.generate(minX, minZ, data, executor, consumer));
        } catch (RuntimeException | LinkageError error) {
            LodgenConfig.LOGGER.error("Cannot start FEATURES generation through the normal chunk pipeline", error);
            callback.setReturnValue(CompletableFuture.failedFuture(error));
        }
    }

    @Inject(method = "close", at = @At("HEAD"))
    private synchronized void lodgen$close(CallbackInfo callback) {
        lodgen$closed = true;
        if (lodgen$generator != null) lodgen$generator.close();
    }
}
