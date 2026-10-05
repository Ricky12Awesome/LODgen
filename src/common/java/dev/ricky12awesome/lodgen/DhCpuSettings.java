package dev.ricky12awesome.lodgen;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.quickOptions.EDhApiThreadPreset;
import com.seibel.distanthorizons.api.methods.events.DhApiEventRegister;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiEventParam;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.api.DhApiConfigValue;
import dev.ricky12awesome.lodgen.generation.GenerationBudget;

import java.util.function.Consumer;

/** Loaded only with DH present; apply the latest CPU load once its API is ready. */
final class DhCpuSettings extends DhApiAfterDhInitEvent implements Consumer<LodgenConfig> {
    void register() {
        var result = DhApiEventRegister.on(DhApiAfterDhInitEvent.class, this);
        if (!result.success) LodgenConfig.LOGGER.error("Cannot register DH CPU load override: {}", result.message);
        LodgenConfig.listen(this);
    }

    @Override public void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
        accept(LodgenConfig.INSTANCE);
    }

    @Override public void accept(LodgenConfig settings) {
        var configs = DhApi.Delayed.configs;
        if (configs == null) return;
        var budget = GenerationBudget.forCpuLoad(settings.cpuLoad(), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory());
        // DH's CPU Load row is separate from the advanced threading entries.
        // CUSTOM locks the row with our API owner without scheduling DH's preset writer.
        boolean preset = new DhApiConfigValue<>(Config.Client.threadPresetSetting).setValue(EDhApiThreadPreset.CUSTOM, "LODgen");
        var threading = configs.multiThreading();
        boolean threads = threading.threadCount().setValue(budget.threads(), "LODgen");
        boolean ratio = threading.threadRuntimeRatio().setValue(budget.runRatio(), "LODgen");
        if (!preset || !threads || !ratio) LodgenConfig.LOGGER.error("DH rejected the LODgen CPU load override");
    }
}
