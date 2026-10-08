package dev.ricky12awesome.lodgen.minecraft;

import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.generation.GenerationProgress;
import dev.ricky12awesome.lodgen.generation.ThroughputDisplay;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** Sends the generation action bar to operators on dedicated servers. */
public final class ServerOperatorOverlay {
    private static final Map<MinecraftServer, ServerOperatorOverlay> SERVERS = new HashMap<>();
    private final Map<ServerLevel, ThroughputDisplay> displays = new HashMap<>();

    public static void tick(MinecraftServer server) {
        if (!server.isDedicatedServer()) return;
        ServerOperatorOverlay overlay;
        synchronized (SERVERS) { overlay = SERVERS.computeIfAbsent(server, ignored -> new ServerOperatorOverlay()); }
        overlay.update(server);
    }

    public static void remove(MinecraftServer server) {
        synchronized (SERVERS) { SERVERS.remove(server); }
    }

    private void update(MinecraftServer server) {
        var playerList = server.getPlayerList();
        if (playerList.getPlayers().isEmpty()) return;
        var sources = new HashMap<ServerLevel, GenerationTasks.DisplaySource>();
        var recipients = new HashMap<ServerLevel, ArrayList<ServerPlayer>>();
        for (ServerPlayer player : playerList.getPlayers()) {
            var playerSource = player.createCommandSourceStack();
            if (!isOperator(playerSource)) continue;
            var source = GenerationTasks.displaySource(playerSource.getLevel());
            sources.putIfAbsent(source.level(), source);
            recipients.computeIfAbsent(source.level(), ignored -> new ArrayList<>()).add(player);
        }
        if (recipients.isEmpty()) return;

        int interval = LodgenConfig.INSTANCE.chunksPerSecondUpdateIntervalMs();
        for (var entry : recipients.entrySet()) {
            ServerLevel level = entry.getKey();
            ThroughputDisplay display = displays.computeIfAbsent(level, ignored -> new ThroughputDisplay());
            if (!display.refresh(PersistenceRegistry.throughput(level), interval)) continue;

            double rate = display.rate();
            var progress = sources.get(level).progress();
            if (rate <= 0 || progress.state() != GenerationProgress.State.RUNNING) continue;

            Component message = CommandText.message("lodgen.overlay.message", String.format(Locale.ROOT, "%.1f", rate),
                    progress.radius(), CommandText.duration(progress.estimatedSeconds(rate)),
                    CommandText.message("lodgen.overlay.state." + progress.state().name().toLowerCase(Locale.ROOT)));
            for (var player : entry.getValue()) player.sendSystemMessage(message, true);
        }
    }

    private static boolean isOperator(CommandSourceStack source) {
        // Match the permission gate on the /lodgen command root.
        // #if MC_1211
        return source.hasPermission(3);
        // #else
        return Commands.hasPermission(Commands.LEVEL_ADMINS).test(source);
        // #endif
    }
}
