package dev.ricky12awesome.lodgen.minecraft;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IServerLevelWrapper;
import dev.ricky12awesome.lodgen.util.Futures;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;

/** Deterministic DH lifecycle races run inside the headless Minecraft fixture. */
public final class DhTaskSinkCheck {
    private boolean loaded = true;
    private IDhLevel destination = destination();
    private final IServerLevelWrapper wrapper;
    private final ServerLevel level;

    private DhTaskSinkCheck(ServerLevel level) {
        this.level = level;
        wrapper = proxy(IServerLevelWrapper.class, (method, args) -> switch (method) {
            case "getWrappedMcObject" -> level;
            case "getDhLevel" -> destination;
            default -> throw new AssertionError(method);
        });
    }

    public static void run(ServerLevel level) {
        var original = DhApi.Delayed.worldProxy;
        try { new DhTaskSinkCheck(level).check(); }
        finally { DhApi.Delayed.worldProxy = original; }
    }

    private void installProxy() {
        DhApi.Delayed.worldProxy = proxy(IDhApiWorldProxy.class, (method, args) -> switch (method) {
            case "worldLoaded" -> loaded;
            case "getAllLoadedLevelWrappers" -> loaded ? List.of(wrapper) : List.of();
            default -> throw new AssertionError(method);
        });
    }

    private void check() {
        DhApi.Delayed.worldProxy = null;
        cancelled(DhTaskSink.convert(level, level, List.of(), Runnable::run));
        installProxy(); loaded = false;
        cancelled(DhTaskSink.convert(level, level, List.of(), Runnable::run));
        loaded = true; destination = null;
        cancelled(DhTaskSink.convert(level, level, List.of(), Runnable::run));
        destination = destination();

        DhApi.Delayed.worldProxy = proxy(IDhApiWorldProxy.class, (method, args) -> switch (method) {
            case "worldLoaded" -> loaded;
            case "getAllLoadedLevelWrappers" -> {
                loaded = false;
                throw new IllegalStateException("World closed between API calls");
            }
            default -> throw new AssertionError(method);
        });
        cancelled(DhTaskSink.convert(level, level, List.of(), Runnable::run));

        installProxy(); loaded = true;
        var queued = new ArrayDeque<Runnable>();
        var future = DhTaskSink.convert(level, level, List.of(), queued::add);
        require(!future.isDone(), "Conversion did not wait for its worker");
        loaded = false;
        queued.removeFirst().run();
        cancelled(future);

        loaded = true;
        future = DhTaskSink.convert(level, level, List.of(), queued::add);
        destination = destination();
        queued.removeFirst().run();
        cancelled(future);

        var failure = new RejectedExecutionException("Worker failed");
        future = DhTaskSink.convert(level, level, List.of(), task -> { throw failure; });
        try { future.join(); throw new AssertionError("Genuine worker failure was swallowed"); }
        catch (CompletionException error) { require(Futures.rootCause(error) == failure, "Open destination hid a genuine failure"); }
    }

    /** Hide DH after a native batch starts, as when the render thread disconnects. */
    public static AutoCloseable hideWorld() {
        var original = DhApi.Delayed.worldProxy;
        DhApi.Delayed.worldProxy = proxy(IDhApiWorldProxy.class, (method, args) -> switch (method) {
            case "worldLoaded" -> false;
            case "getAllLoadedLevelWrappers" -> List.of();
            default -> throw new AssertionError(method);
        });
        return () -> DhApi.Delayed.worldProxy = original;
    }

    private static IDhLevel destination() {
        return proxy(IDhLevel.class, (method, args) -> {
            throw new AssertionError("Closed/replacement destination received work: " + method);
        });
    }

    private static void cancelled(CompletableFuture<Void> future) {
        require(future.isCompletedExceptionally(), "Expected cancellation");
        try { future.join(); throw new AssertionError("Conversion succeeded after DH closed"); }
        catch (RuntimeException error) { require(Futures.rootCause(error) instanceof CancellationException, "Shutdown paused the task: " + error); }
    }

    private static void require(boolean condition, String text) { if (!condition) throw new AssertionError(text); }
    @FunctionalInterface private interface Handler { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(method.getName(), args)));
    }
}
