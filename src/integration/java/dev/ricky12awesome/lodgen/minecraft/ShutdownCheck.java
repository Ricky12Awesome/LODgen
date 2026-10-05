package dev.ricky12awesome.lodgen.minecraft;

import dev.ricky12awesome.lodgen.generation.CaveMode;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

/** Quit with 64 native LOD targets still generating, without a client window. */
public final class ShutdownCheck {
    private static Path terrain;
    private static ServerLevel source;
    private static Throwable failure;

    public static void run(MinecraftServer server) {
        try {
            var existing = temporaryDirectories();
            source = LodGenerationWorld.get(server.overworld(), CaveMode.EMPTY);
            var created = temporaryDirectories();
            created.removeAll(existing);
            if (LodGenerationWorld.mode(source) != CaveMode.EMPTY || created.size() != 1)
                throw new IllegalStateException("Shutdown fixture did not create one isolated Empty world");
            terrain = created.iterator().next();
            var request = PersistenceRegistry.backend(source).request(6144, -6144, 8, 8, null);
            if (request.isDone()) throw new IllegalStateException("Shutdown fixture needs active native generation");
            if (!source.getChunkSource().chunkMap.hasWork())
                throw new IllegalStateException("Shutdown fixture needs admitted native chunk holders");
            request.thenAccept(batch -> batch.release());
            dev.ricky12awesome.lodgen.LodgenConfig.LOGGER.info("SHUTDOWN CHECK: stopping with 64 active Empty LOD targets in {}", terrain);
            server.halt(false);
        } catch (Throwable error) {
            failure = error;
            report("FAIL: " + error);
            server.halt(false);
        }
    }

    public static void stopped(MinecraftServer server) {
        if (!Boolean.getBoolean("lodgen.test.shutdown")) return;
        report(failure != null ? "FAIL: " + failure : source != null && source.getChunkSource().chunkMap.hasWork()
                ? "FAIL: temporary LOD chunk holders survived storage closure"
                : terrain != null && !Files.exists(terrain)
                ? "PASS: shutdown drained active Empty LOD generation and removed temporary terrain."
                : "FAIL: temporary LOD terrain survived shutdown");
    }

    private static void report(String result) {
        try { Files.writeString(Path.of("integration-result.txt"), result + "\n"); }
        catch (Exception error) { throw new IllegalStateException("Cannot write shutdown result", error); }
    }

    private static Set<Path> temporaryDirectories() throws java.io.IOException {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(path -> path.getFileName().toString().startsWith("lodgen-world-"))
                    .filter(Files::isDirectory).collect(Collectors.toSet());
        }
    }
}
