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

/** Loaded only with DH present; apply generation threads once its API is ready. */
final class DhThreadSettings extends DhApiAfterDhInitEvent implements Consumer<LodgenConfig> {
    private GenerationBudget applied;
    void register() {
        var result = DhApiEventRegister.on(DhApiAfterDhInitEvent.class, this);
        if (!result.success) LodgenConfig.LOGGER.error("Cannot register DH generation thread override: {}", result.message);
        LodgenConfig.listen(this);
    }

    @Override public synchronized void afterDistantHorizonsInit(DhApiEventParam<Void> event) {
        applied = null;
        accept(LodgenConfig.INSTANCE);
    }

    @Override public synchronized void accept(LodgenConfig settings) {
        var configs = DhApi.Delayed.configs;
        if (configs == null) return;
        var budget = GenerationSettings.budget(settings);
        if (applied != null && applied.threads() == budget.threads() && applied.runRatio() == budget.runRatio()) return;
        // DH's CPU Load row is separate from the advanced threading entries.
        // CUSTOM locks the row with our API owner without scheduling DH's preset writer.
        boolean preset = applied != null
                || new DhApiConfigValue<>(Config.Client.threadPresetSetting).setValue(EDhApiThreadPreset.CUSTOM, "LODgen");
        var threading = configs.multiThreading();
        boolean threads = applied != null && applied.threads() == budget.threads()
                || threading.threadCount().setValue(budget.threads(), "LODgen");
        boolean ratio = applied != null && applied.runRatio() == budget.runRatio()
                || threading.threadRuntimeRatio().setValue(budget.runRatio(), "LODgen");
        if (!preset || !threads || !ratio) LodgenConfig.LOGGER.error("DH rejected the LODgen generation thread override");
        else applied = budget;
    }
}
