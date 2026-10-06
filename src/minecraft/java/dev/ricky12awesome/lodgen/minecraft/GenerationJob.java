package dev.ricky12awesome.lodgen.minecraft;

import dev.ricky12awesome.lodgen.GenerationSettings;
import dev.ricky12awesome.lodgen.LodgenConfig;
import dev.ricky12awesome.lodgen.generation.GenerationProgress;
import dev.ricky12awesome.lodgen.task.SquarePlan;
import dev.ricky12awesome.lodgen.task.TaskProgress;
import dev.ricky12awesome.lodgen.task.TaskRecord;
import dev.ricky12awesome.lodgen.task.TaskStore;
import dev.ricky12awesome.lodgen.util.Futures;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** One task's dispatch, renderer output, progress, and checkpoint lifetime. */
final class GenerationJob implements AutoCloseable {
    private static final int MAX_DISPATCHES = 256;
    private static final long CHECKPOINT_INTERVAL = TimeUnit.SECONDS.toNanos(5);
    final ServerLevel level;
    final TaskRecord record;
    final TaskProgress progress;
    final AutomaticSettings settings;
    volatile GenerationProgress displayProgress;
    private final SquarePlan plan;
    private final Path path;
    private final ExecutorService workers;
    private final BooleanSupplier serverClosing;
    private final Predicate<GenerationJob> currentJob;
    private final Map<Long, CompletableFuture<Void>> active = new HashMap<>();
    private final ChunkGenerationPipeline pipeline;
    private CompletableFuture<Void> surface;
    private volatile boolean stopped;
    private boolean waiting;
    private long lastCheckpoint;

    GenerationJob(ServerLevel level, TaskRecord record, Path path, ExecutorService workers,
                  BooleanSupplier serverClosing, Predicate<GenerationJob> currentJob) {
        this.level = level;
        this.record = record;
        this.path = path;
        this.workers = workers;
        this.serverClosing = serverClosing;
        this.currentJob = currentJob;
        settings = AutomaticSettings.restored(level, record);
        plan = new SquarePlan(record.area());
        progress = new TaskProgress(plan, record.progress());
        pipeline = new ChunkGenerationPipeline(level, record.caveMode());
        publishProgress();
    }

    void pause() {
        if (progress.state() != TaskProgress.State.RUNNING) throw new IllegalStateException("lodgen.command.error.notRunning");
        progress.pause();
        publishProgress();
        checkpoint();
    }

    boolean explicitActive() {
        var state = progress.state();
        return !record.automatic() && (state == TaskProgress.State.RUNNING || state == TaskProgress.State.PAUSED);
    }

    void resume() {
        progress.resume();
        publishProgress();
        checkpoint();
    }

    void stop() {
        progress.stop();
        close();
        publishProgress();
        checkpoint();
    }

    void tick() {
        if (!canDispatch() || !rendererReady() || !surfaceReady()) return;
        int limit = GenerationSettings.current().batches();
        for (int budget = 0; budget < MAX_DISPATCHES && active.size() < limit; budget++) {
            long index = progress.next();
            if (index < 0) break;
            if (!active.containsKey(index)) startBatch(index, plan.batch(index));
        }
        publishProgress();
    }

    private boolean interrupted() { return stopped || serverClosing.getAsBoolean(); }

    private boolean canDispatch() {
        if (interrupted() || progress.state() != TaskProgress.State.RUNNING) return false;
        // Completion callbacks refill directly; apply the live policy there too.
        if (record.automatic() && !automaticAllowed()) { publishProgress(); return false; }
        return !record.automatic() || settings.equals(AutomaticSettings.current(level));
    }

    private boolean rendererReady() {
        try { waiting = !RendererSinks.ready(level, record.dh(), record.voxy()); }
        catch (RuntimeException | LinkageError error) {
            fail(error);
            LodgenConfig.LOGGER.error("Cannot initialize LODgen task output", error);
            return false;
        }
        if (waiting) publishProgress();
        return !waiting;
    }

    private boolean surfaceReady() {
        if (!record.automatic() || !record.dh() || record.area().radius() == 0 || !GenerationSettings.policy().surfaceFirst()) return true;
        if (surface == null) {
            surface = RendererSinks.surface(level, record.area(), workers, () -> !interrupted()
                    && currentJob.test(this) && progress.state() == TaskProgress.State.RUNNING && automaticAllowed());
            surface.whenComplete((ignored, error) -> level.getServer().execute(() -> finishSurface(error)));
        }
        return surface != null && surface.isDone() && !surface.isCompletedExceptionally();
    }

    private void finishSurface(Throwable error) {
        if (error != null && Futures.rootCause(error) instanceof CancellationException) surface = null;
        else if (error != null && !interrupted()) {
            fail(error);
            LodgenConfig.LOGGER.error("Automatic DH surface generation failed", error);
        } else tick();
    }

    private void startBatch(long index, SquarePlan.Batch batch) {
        var future = pipeline.generate(batch.x(), batch.z(), batch.width(), batch.height(), record.area(), workers, this::convert);
        active.put(index, future);
        future.whenComplete((ignored, error) -> level.getServer().execute(() -> finishBatch(index, batch, error)));
    }

    private CompletableFuture<Void> convert(NormalChunkBackend.Batch batch) {
        if (interrupted()) return CompletableFuture.failedFuture(new CancellationException("LODgen task is closing"));
        var lodChunks = batch.chunks.stream().filter(chunk -> record.area().lods(
                PersistenceRegistry.x(chunk.getPos()), PersistenceRegistry.z(chunk.getPos()))).toList();
        return RendererSinks.convert(level, batch.sourceLevel(), lodChunks, workers, record.dh(), record.voxy());
    }

    private void finishBatch(long index, SquarePlan.Batch batch, Throwable error) {
        active.remove(index);
        if (error == null) progress.complete(index);
        else {
            progress.retry(index);
            if (!interrupted() && progress.error().isEmpty()
                    && !(Futures.rootCause(error) instanceof CancellationException)) {
                progress.fail(Futures.rootCause(error).toString());
                LodgenConfig.LOGGER.error("LODgen task paused at {},{}", batch.x(), batch.z(), error);
            }
        }
        publishProgress();
        if (progress.state() != TaskProgress.State.RUNNING || System.nanoTime() - lastCheckpoint >= CHECKPOINT_INTERVAL) checkpoint();
        // Refill immediately after generation, conversion, and cleanup drain.
        tick();
    }

    private boolean automaticAllowed() {
        var policy = GenerationSettings.policy();
        return policy.automatic() && (!record.dh() || policy.features() || record.area().savedRadius() >= record.area().radius());
    }

    private void fail(Throwable error) {
        progress.fail(Futures.rootCause(error).toString());
        publishProgress();
        checkpoint();
    }

    private void publishProgress() {
        var state = record.automatic() && progress.state() == TaskProgress.State.RUNNING && !automaticAllowed() ? GenerationProgress.State.DISABLED
                : waiting && progress.state() == TaskProgress.State.RUNNING ? GenerationProgress.State.WAITING_FOR_RENDERER
                : GenerationProgress.State.valueOf(progress.state().name());
        displayProgress = new GenerationProgress(record.area().radius(), plan.chunks() - progress.completedChunks(), state);
    }

    String status() {
        return CommandText.plain(statusMessage());
    }
    Component statusMessage() {
        long done = progress.completedChunks();
        double rate = PersistenceRegistry.throughput(level).chunksPerSecond();
        return CommandText.message("lodgen.command.status.task",
                record.automatic() ? CommandText.message("lodgen.command.status.automatic") : Component.empty(),
                CommandText.message("lodgen.command.state." + displayProgress.state().name().toLowerCase(Locale.ROOT)),
                record.dimension(), record.area().blockX(), record.area().blockZ(),
                record.area().radius(), (long) record.area().radius() * 16, record.area().savedRadius(), (long) record.area().savedRadius() * 16,
                CommandText.message("lodgen.command.cave." + record.caveMode().name().toLowerCase(Locale.ROOT)),
                done, plan.chunks(), String.format(Locale.ROOT, "%.2f", done * 100.0 / plan.chunks()), active.size(), String.format(Locale.ROOT, "%.1f", rate),
                CommandText.duration(displayProgress.estimatedSeconds(rate)), progress.error().isEmpty() ? Component.empty()
                        : CommandText.message("lodgen.command.status.error", progress.error()));
    }

    void checkpoint() {
        if (!currentJob.test(this)) return;
        try {
            TaskStore.write(path, new TaskRecord(record.dimension(), record.area(), record.dh(), record.voxy(), record.automatic(), progress.snapshot(), record.caveMode()));
            lastCheckpoint = System.nanoTime();
        } catch (Exception error) { LodgenConfig.LOGGER.error("Cannot checkpoint LODgen task at {}", path, error); }
    }

    @Override public void close() { stopped = true; pipeline.close(); }
}
