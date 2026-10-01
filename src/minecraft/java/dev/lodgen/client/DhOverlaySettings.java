package dev.lodgen.client;

import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorProgressDisplayLocation;
import com.seibel.distanthorizons.core.config.Config;

/** Loaded only after the optional DH installation has been detected. */
final class DhOverlaySettings {
    static boolean usesOverlay() {
        return Config.Common.WorldGenerator.showGenerationProgress.get()
                == EDhApiDistantGeneratorProgressDisplayLocation.OVERLAY;
    }
}
