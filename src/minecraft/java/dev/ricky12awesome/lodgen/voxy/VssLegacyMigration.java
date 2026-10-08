package dev.ricky12awesome.lodgen.voxy;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/** Read-only cutover of the old sidecar into VSS's own validated writer. */
public final class VssLegacyMigration {
    private static final Map<Object, CompletableFuture<Integer>> RUNS = new WeakHashMap<>();
    private record Identity(String complete, String privacy) {}
    private VssLegacyMigration() {}

    public static boolean required(MinecraftServer server) {
        var path = server.getWorldPath(LevelResource.ROOT).resolve("lodgen/vss-lods.sqlite");
        return Files.isRegularFile(path) && !VssMigrationMarker.completed(path.resolveSibling("vss-native-migration"), path);
    }
    /** Main-thread probe: pending mask discovery must not permanently fail a migration. */
    public static boolean masksReady(MinecraftServer server) {
        try {
            Class<?> manager = Class.forName("dev.vox.lss.networking.server.XrayMaskManager");
            for (var level : server.getAllLevels()) if (!(boolean) call(manager, "isTerminalForActive", level)) return false;
            return true;
        } catch (Exception pending) { return false; }
    }
    /** Call once a live native service is ready. The returned count includes existing native rows. */
    public static CompletableFuture<Integer> start(ServerLevel level, Object service, Executor workers) {
        if (!required(level.getServer()))
            return CompletableFuture.completedFuture(0);
        try {
            Object store = call(service, "getLodStore");
            if (store == null) return CompletableFuture.failedFuture(new IllegalStateException("Native VSS cache is unavailable for migration"));
            synchronized (RUNS) {
                var existing = RUNS.get(store);
                if (existing != null && !existing.isCompletedExceptionally() && !existing.isCancelled()) return existing;
                if (!masksReady(level.getServer())) return CompletableFuture.failedFuture(
                        new IllegalStateException("VSS privacy masks are not ready for migration"));
                var future = CompletableFuture.supplyAsync(() -> snapshot(level.getServer(), service), level.getServer())
                        .orTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .thenApplyAsync(identity -> migrate(level, service, store, identity), workers);
                RUNS.put(store, future); return future;
            }
        } catch (Exception error) { return CompletableFuture.failedFuture(error); }
    }
    private static int migrate(ServerLevel level, Object service, Object store, Identity identity) {
        var server = level.getServer();
        var path = server.getWorldPath(LevelResource.ROOT).resolve("lodgen/vss-lods.sqlite");
        if (!Files.isRegularFile(path)) return 0;
        try {
            var metadata = VssMigrationMarker.source(path);
            var driver = Class.forName("dev.vox.lss.common.store.SqliteDriverRuntime");
            var source = (DataSource) driver.getMethod("dataSource", String.class)
                    .invoke(null, "jdbc:sqlite:" + path.toUri().toASCIIString() + "?mode=ro");
            try (var connection = source.getConnection(); var query = connection.createStatement()) {
                try (var rows = query.executeQuery("SELECT value FROM identity")) {
                    if (!rows.next() || !identity.complete().equals(rows.getString(1)) || rows.next())
                        throw new IllegalStateException("Legacy VSS cache registry or privacy identity differs; original cache was preserved");
                }
                Object codec = Class.forName("dev.vox.lss.common.store.StoreCodec").getMethod("zstdOrNull").invoke(null);
                if (codec == null) throw new IllegalStateException("Native VSS codec is unavailable for migration");
                int count = 0;
                var batch = new ArrayList<VssNativeStore.Column>(64);
                try (var rows = query.executeQuery("SELECT dimension,position,frame,size,stamp FROM columns ORDER BY dimension,position")) {
                    while (rows.next()) {
                        String dimension = rows.getString(1);
                        long position = rows.getLong(2), stamp = rows.getLong(5);
                        if (stamp <= 0) throw new IllegalStateException("Legacy VSS cache has invalid acquisition timestamp");
                        Object hit = VssNativeStore.invoke(store, "get", new Class<?>[]{String.class, long.class}, dimension, position);
                        if (hit != null && (long) call(hit, "columnTimestamp") >= stamp) { count++; continue; }
                        int size = rows.getInt(4);
                        if (size < 0 || size > (16 << 20)) throw new IllegalStateException("Legacy VSS cache has invalid column size");
                        byte[] frame = rows.getBytes(3);
                        byte[] bytes = size == 0 ? new byte[0]
                                : (byte[]) VssNativeStore.invoke(codec, "decompress", new Class<?>[]{byte[].class, int.class}, frame, size);
                        if (bytes == null || bytes.length != size) throw new IllegalStateException("Legacy VSS cache has invalid compressed column");
                        batch.add(new VssNativeStore.Column(dimension, position, bytes, stamp));
                        count++;
                        if (batch.size() == 64) { commit(server, service, store, identity, batch); batch.clear(); }
                    }
                }
                if (!batch.isEmpty()) commit(server, service, store, identity, batch);
                String finalPrivacy = CompletableFuture.supplyAsync(() -> privacy(server, service), server)
                        .get(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!identity.privacy().equals(finalPrivacy)) throw new IllegalStateException("VSS privacy changed during legacy migration");
                VssNativeStore.flush(store, () -> !server.isStopped() && active(service));
                VssMigrationMarker.complete(path.resolveSibling("vss-native-migration"), path, metadata, identity.complete(), count);
                return count;
            }
        } catch (Exception error) { throw new CompletionException(error); }
    }
    private static void commit(MinecraftServer server, Object service, Object store, Identity identity, ArrayList<VssNativeStore.Column> batch) throws Exception {
        if (server.isStopped() || !active(service)) throw new java.util.concurrent.CancellationException("VSS migration service closed");
        String current = CompletableFuture.supplyAsync(() -> privacy(server, service), server)
                .get(30, java.util.concurrent.TimeUnit.SECONDS);
        if (!identity.privacy().equals(current)) throw new IllegalStateException("VSS privacy changed during legacy migration; original cache was preserved");
        VssNativeStore.commit(store, batch, () -> !server.isStopped() && active(service));
    }
    private static boolean active(Object service) {
        try { return call(Class.forName("dev.vox.lss.networking.server.LSSServerNetworking"), "getRequestService") == service; }
        catch (Exception error) { return false; }
    }
    private static Identity snapshot(MinecraftServer server, Object service) {
        try {
            Object settings = call(call(service, "settingsConfig"), "snapshot");
            var states = new StringBuilder("v20\n").append(server.getServerVersion()).append('\n').append(call(settings, "privacy"));
            for (var state : Block.BLOCK_STATE_REGISTRY) states.append('\n').append(state);
            // #if MC_1211
            var biomes = server.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME);
            // #else
            var biomes = server.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
            // #endif
            for (var biome : biomes) states.append('\n').append(biomes.getKey(biome));
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(states.toString().getBytes(StandardCharsets.UTF_8)));
            String masks = masks(server);
            return new Identity(hash + masks, call(settings, "privacy") + masks);
        } catch (Exception error) { throw new CompletionException(error); }
    }
    private static String privacy(MinecraftServer server, Object service) {
        try { return call(call(call(service, "settingsConfig"), "snapshot"), "privacy") + masks(server); }
        catch (Exception error) { throw new CompletionException(error); }
    }
    private static String masks(MinecraftServer server) throws Exception {
        var result = new TreeMap<String, String>();
        Class<?> manager = Class.forName("dev.vox.lss.networking.server.XrayMaskManager");
        for (var level : server.getAllLevels()) {
            if (!(boolean) call(manager, "isTerminalForActive", level)) throw new IllegalStateException("VSS privacy masks are not ready for migration");
            Object entry = call(manager, "entryForActive", level);
            String fingerprint = entry == null ? "off" : call(entry, "sourceLabel") + ":" + call(call(entry, "mask"), "fingerprint");
            result.put(level.dimension().toString(), level.getSeed() + ":" + fingerprint);
        }
        return result.toString();
    }
    private static Object call(Object target, String name, Object... arguments) throws Exception {
        Class<?> owner = target instanceof Class<?> type ? type : target.getClass();
        for (var method : owner.getMethods()) if (method.getName().equals(name) && method.getParameterCount() == arguments.length) {
            try { return method.invoke(target instanceof Class<?> ? null : target, arguments); }
            catch (java.lang.reflect.InvocationTargetException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                throw error;
            }
        }
        throw new NoSuchMethodException(owner.getName() + "." + name);
    }
}
