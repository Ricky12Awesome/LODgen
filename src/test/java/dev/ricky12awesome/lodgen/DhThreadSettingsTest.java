package dev.ricky12awesome.lodgen;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.quickOptions.EDhApiThreadPreset;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiAfterDhInitEvent;
import com.seibel.distanthorizons.core.api.external.methods.config.DhApiConfig;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.config.api.DhApiConfigValue;
import com.seibel.distanthorizons.coreapi.DependencyInjection.ApiEventInjector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DhThreadSettingsTest {
    private final DhThreadSettings override = new DhThreadSettings();
    private final LodgenConfig original = LodgenConfig.INSTANCE;
    private final com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig originalApi = DhApi.Delayed.configs;
    private int savedThreads;
    private double savedRatio;
    private EDhApiThreadPreset savedPreset;

    @BeforeEach void prepare() {
        // DH initializes its preset handler before the entries that use its defaults.
        assertNotNull(com.seibel.distanthorizons.core.config.eventHandlers.presets.ThreadPresetConfigEventHandler.INSTANCE);
        // The preset GUI listeners require a running game; exercise the API entries headlessly.
        Config.Common.MultiThreading.numberOfThreads.clearListeners();
        Config.Common.MultiThreading.threadRunTimeRatio.clearListeners();
        Config.Client.threadPresetSetting.clearListeners();
        savedThreads = Config.Common.MultiThreading.numberOfThreads.getTrueValue();
        savedRatio = Config.Common.MultiThreading.threadRunTimeRatio.getTrueValue();
        savedPreset = Config.Client.threadPresetSetting.getTrueValue();
        Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(7);
        Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(0.25);
        DhApi.Delayed.configs = null;
    }

    @AfterEach void restore() throws Exception {
        LodgenConfig.stopListening(override);
        ApiEventInjector.INSTANCE.unbind(DhApiAfterDhInitEvent.class, DhThreadSettings.class);
        DhApiConfig.INSTANCE.multiThreading().threadCount().clearValue();
        DhApiConfig.INSTANCE.multiThreading().threadRuntimeRatio().clearValue();
        new DhApiConfigValue<>(Config.Client.threadPresetSetting).clearValue();
        Config.Common.MultiThreading.numberOfThreads.setWithoutFiringEvents(savedThreads);
        Config.Common.MultiThreading.threadRunTimeRatio.setWithoutFiringEvents(savedRatio);
        Config.Client.threadPresetSetting.setWithoutFiringEvents(savedPreset);
        DhApi.Delayed.configs = originalApi;
        LodgenConfig.apply(original);
    }

    @Test void defersUntilDhInitAndAppliesLatestSettingsLive() throws Exception {
        override.register();
        LodgenConfig.apply(LodgenConfig.SCHEMA.with(original, "threads", 1));
        assertNull(Config.Common.MultiThreading.numberOfThreads.getApiValue());
        assertNull(Config.Client.threadPresetSetting.getApiValue());

        DhApi.Delayed.configs = DhApiConfig.INSTANCE;
        ApiEventInjector.INSTANCE.fireAllEvents(DhApiAfterDhInitEvent.class, null);
        assertOverride();
        assertEquals(1, Config.Common.MultiThreading.numberOfThreads.get());
        assertEquals(1, Config.Common.MultiThreading.threadRunTimeRatio.get());

        for (int threads : new int[]{1, LodgenConfig.DEFAULTS.threads(), Runtime.getRuntime().availableProcessors()}) {
            LodgenConfig.apply(LodgenConfig.SCHEMA.with(original, "threads", threads));
            assertOverride();
        }

        Config.Common.MultiThreading.numberOfThreads.setWithoutSaving(2);
        Config.Common.MultiThreading.threadRunTimeRatio.setWithoutSaving(0.1);
        assertOverride();
        assertEquals(2, Config.Common.MultiThreading.numberOfThreads.getTrueValue());
        assertEquals(0.1, Config.Common.MultiThreading.threadRunTimeRatio.getTrueValue());
    }

    @Test void appliesImmediatelyWhenDhIsAlreadyReadyIncludingWithAutomaticGenerationOff() throws Exception {
        LodgenConfig.apply(LodgenConfig.SCHEMA.with(LodgenConfig.SCHEMA.with(original, "enabled", false), "threads", 1));
        DhApi.Delayed.configs = DhApiConfig.INSTANCE;
        override.register();
        assertOverride();
        assertEquals(7, Config.Common.MultiThreading.numberOfThreads.getTrueValue());
        assertEquals(0.25, Config.Common.MultiThreading.threadRunTimeRatio.getTrueValue());
        assertEquals(savedPreset, Config.Client.threadPresetSetting.getTrueValue());
    }

    @Test void slowTicksReduceDutyCycleAndPreventLagCanBeDisabledLive() throws Exception {
        var server = new Object();
        try {
            DhApi.Delayed.configs = DhApiConfig.INSTANCE;
            override.register();
            LodgenConfig.apply(LodgenConfig.SCHEMA.with(original, "preventLag", true));
            for (int i = 0; i < 10; i++) GenerationSettings.serverTick(server, 100_000_000L);
            override.accept(LodgenConfig.INSTANCE);
            assertTrue(GenerationSettings.current().runRatio() < 1);
            assertEquals(LodgenConfig.INSTANCE.threads(), GenerationSettings.current().threads());
            assertOverride();

            LodgenConfig.apply(LodgenConfig.SCHEMA.with(LodgenConfig.INSTANCE, "preventLag", false));
            assertEquals(1, GenerationSettings.current().runRatio());
            assertOverride();
            for (int i = 0; i < 200; i++) GenerationSettings.serverTick(server, 5_000_000L);
            LodgenConfig.apply(LodgenConfig.SCHEMA.with(LodgenConfig.INSTANCE, "preventLag", true));
            assertEquals(1, GenerationSettings.current().runRatio());
            assertOverride();
        } finally { GenerationSettings.serverStopped(server); }
    }

    private static void assertOverride() {
        assertTrue(Config.Client.threadPresetSetting.apiIsOverriding(), "DH's CPU Load row must show its API lock");
        assertEquals("LODgen", Config.Client.threadPresetSetting.getApiUser(), "DH's ownership tooltip must name LODgen");
        assertEquals(EDhApiThreadPreset.CUSTOM, Config.Client.threadPresetSetting.getApiValue());
        var budget = GenerationSettings.current();
        assertEquals(budget.threads(), Config.Common.MultiThreading.numberOfThreads.get());
        assertEquals(budget.threads(), Config.Common.MultiThreading.numberOfThreads.getApiValue());
        assertEquals(budget.runRatio(), Config.Common.MultiThreading.threadRunTimeRatio.get());
        assertEquals(budget.runRatio(), Config.Common.MultiThreading.threadRunTimeRatio.getApiValue());
        assertEquals("LODgen", Config.Common.MultiThreading.numberOfThreads.getApiUser());
        assertEquals("LODgen", Config.Common.MultiThreading.threadRunTimeRatio.getApiUser());
    }
}
