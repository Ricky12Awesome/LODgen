package dev.lodgen.startup;

import dev.lodgen.LodgenConfig;
import dev.lodgen.minecraft.ChunkGenerationPipeline;
import dev.lodgen.minecraft.PersistenceRegistry;
import dev.lodgen.voxy.VoxyBridge;
import dev.lodgen.voxy.VoxyGeneration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Small real single-player regression with Voxy/C2ME and no DH installed. */
public final class VoxyWorldCheck {
    private static long started;
    private static long idleStarted;
    private static int stage;
    private static int completedTiles;
    private static CompletableFuture<Void> far;

    public static void start(Minecraft client) throws Exception {
        started = System.nanoTime();
        // Keep generation paused while the normal player/spawn chunks load.
        LodgenConfig.apply(new LodgenConfig(false, 1, 0, true, 1, true, 1000));
        if (Boolean.getBoolean("lodgen.test.voxyReload")) {
            client.createWorldOpenFlows().openWorld("lodgen-voxy-check", () -> { throw new AssertionError("World reopen cancelled"); });
            return;
        }
        // #if MC_1211
        var settings = new LevelSettings("LODgen Voxy check", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                true, new net.minecraft.world.level.GameRules(), WorldDataConfiguration.DEFAULT);
        // #else
        var settings = new LevelSettings("LODgen Voxy check", GameType.CREATIVE,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        // #endif
        client.createWorldOpenFlows().createFreshLevel("lodgen-voxy-check", settings,
                new WorldOptions(123456789L, false, false), registries ->
                // #if MC_1211
                registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
                // #else
                registries.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL).value().createWorldDimensions(),
                // #endif
                new TitleScreen());
    }

    public static void tick(Minecraft client) {
        if (stage == 4) return;
        try {
            if (System.nanoTime() - started > TimeUnit.SECONDS.toNanos(100)) throw new AssertionError("Voxy world check timed out");
            if (client.level == null || client.player == null || client.getSingleplayerServer() == null) return;
            var bridge = new VoxyBridge();
            var context = bridge.context(client.level);
            if (context == null) throw new AssertionError("Voxy ingestion unavailable in the real client");
            if (stage == 0) {
                LodgenConfig.apply(new LodgenConfig(true, 1, 0, true, 1, true, 1000));
                stage = 1; return;
            }
            if (stage == 1) {
                var field = VoxyGeneration.class.getDeclaredField("current"); field.setAccessible(true);
                Object session = field.get(null);
                if (session == null) return;
                var active = session.getClass().getDeclaredField("active"); active.setAccessible(true);
                var completed = session.getClass().getDeclaredField("completed"); completed.setAccessible(true);
                var frontierField = session.getClass().getDeclaredField("frontier"); frontierField.setAccessible(true);
                var frontier = (dev.lodgen.generation.GenerationFrontier) frontierField.get(session);
                if (frontier == null || !frontier.exhausted() || !((Set<?>) active.get(session)).isEmpty()) return;
                completedTiles = ((Set<?>) completed.get(session)).size();
                if (completedTiles < 1 || completedTiles > 4) throw new AssertionError("Unexpected small-radius tile count: " + completedTiles);
                if (Boolean.getBoolean("lodgen.test.voxyReload")) {
                    var saved = dev.lodgen.generation.TileCoverage.read(context.coverageFile());
                    if (!saved.equals(completed.get(session))) throw new AssertionError("Coverage did not prevent regeneration after reopening");
                    checkStoredFarSection(context.engine());
                    StartupCheck.report("PASS: Voxy reopened without DH; completed generation coverage restored and nonempty far LOD data survived restart.");
                    stage = 4; client.stop(); return;
                }
                LodgenConfig.apply(new LodgenConfig(false, 1, 0, true, 1, true, 1000));
                VoxyGeneration.beforeShutdown();
                var server = client.getSingleplayerServer();
                var level = server.getLevel(client.level.dimension());
                var pipeline = new ChunkGenerationPipeline(level);
                far = pipeline.generate(4096, -4096, 4, server, batch -> CompletableFuture.runAsync(() -> {
                    if (!PersistenceRegistry.get(level).suppress(4096, -4096)) throw new AssertionError("Voxy-only native chunk lost transient ownership");
                    try {
                        bridge.ingest(context.engine(), VoxyBridge.snapshot(level, batch.chunks));
                        checkStoredFarSection(context.engine());
                    } catch (ReflectiveOperationException error) { throw new RuntimeException(error); }
                }, server));
                far.whenComplete((ignored, error) -> pipeline.close());
                stage = 2; return;
            }
            if (stage == 3) {
                if (System.nanoTime() - idleStarted < TimeUnit.SECONDS.toNanos(6)) return;
                dev.lodgen.client.GenerationOverlay.tick(client);
                // #if MC_262_PLUS
                var actionBar = (dev.lodgen.mixin.ActionBarAccess) client.gui.hud;
                // #else
                var actionBar = (dev.lodgen.mixin.ActionBarAccess) client.gui;
                // #endif
                var message = actionBar.lodgen$actionBarMessage();
                if (message != null && !message.getString().isEmpty()) throw new AssertionError("Zero throughput left action-bar text behind");
                StartupCheck.report("PASS: Voxy without DH generated " + completedTiles
                        + " nearby tiles (radius 1) and 16 far native chunks; vanilla action-bar throughput appeared, cleared at zero, and preserved another message. Total target chunks <= 80.");
                stage = 4; client.stop(); return;
            }
            if (!far.isDone() || !OverlayCheck.rendered) return;
            far.join();
            if (!dev.lodgen.client.GenerationOverlay.visible()) throw new AssertionError("Voxy-only HUD hidden");
            if (PersistenceRegistry.throughput(client.getSingleplayerServer().getLevel(client.level.dimension())).chunksPerSecond() <= 0)
                throw new AssertionError("Successful far batch missing from throughput counter");
            OverlayCheck.checkMessageOwnership(client);
            idleStarted = System.nanoTime();
            stage = 3;

        } catch (Throwable failure) {
            StartupCheck.report("FAIL: " + failure);
            throw new RuntimeException("Voxy world check failed", failure);
        }
    }

    private static void checkStoredFarSection(Object engine) throws ReflectiveOperationException {
        Object section = engine.getClass().getMethod("acquireIfExists", int.class, int.class, int.class, int.class)
                .invoke(engine, 0, 2048, 0, -2048);
        if (section == null) throw new AssertionError("Voxy did not store the far native section");
        try {
            int nonAir = (int) section.getClass().getMethod("getNonEmptyBlockCount").invoke(section);
            if (nonAir == 0) throw new AssertionError("Generated Voxy LOD section is empty");
        } finally { section.getClass().getMethod("release").invoke(section); }
    }
}
