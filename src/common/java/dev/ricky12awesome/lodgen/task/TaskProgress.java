package dev.ricky12awesome.lodgen.task;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** A contiguous completion prefix plus out-of-order completed batches. */
public final class TaskProgress {
    public enum State { RUNNING, PAUSED, STOPPED, COMPLETE }
    public record Snapshot(State state, long prefix, List<Long> beyond, String error) {
        public Snapshot { java.util.Objects.requireNonNull(state); java.util.Objects.requireNonNull(error); beyond = List.copyOf(beyond); }
    }
    private final SquarePlan plan;
    private final TreeSet<Long> beyond = new TreeSet<>();
    private volatile State state = State.RUNNING;
    private long prefix, cursor;
    private String error = "";
    public TaskProgress(SquarePlan plan) { this.plan = plan; }
    public TaskProgress(SquarePlan plan, Snapshot saved) {
        this.plan = plan;
        if (saved.prefix < 0 || saved.prefix > plan.batches()) throw new IllegalArgumentException("Invalid task progress");
        prefix = saved.prefix; state = saved.state; error = saved.error;
        for (long index : saved.beyond) {
            if (index <= prefix || index >= plan.batches() || !beyond.add(index)) throw new IllegalArgumentException("Invalid completed batch");
        }
        if (state == State.COMPLETE && prefix != plan.batches()) throw new IllegalArgumentException("Incomplete task marked complete");
        cursor = prefix;
    }
    public long next() {
        if (state != State.RUNNING) return -1;
        cursor = Math.max(cursor, prefix);
        while (cursor < plan.batches() && beyond.contains(cursor)) cursor++;
        return cursor < plan.batches() ? cursor++ : -1;
    }
    /** Renderer shutdown can cancel a dispatched batch without pausing the task. */
    public void retry(long index) {
        if (index < 0 || index >= plan.batches()) throw new IllegalArgumentException("Invalid batch to retry");
        if (index >= prefix && !beyond.contains(index)) cursor = Math.min(cursor, index);
    }
    public void complete(long index) {
        if (index < prefix || beyond.contains(index)) return;
        if (index >= plan.batches()) throw new IllegalArgumentException("Invalid completed batch");
        if (index == prefix) {
            prefix++;
            while (beyond.remove(prefix)) prefix++;
        } else beyond.add(index);
        if (prefix == plan.batches() && state == State.RUNNING) state = State.COMPLETE;
    }
    public void pause() { if (state == State.RUNNING) state = State.PAUSED; }
    public void fail(String message) { pause(); error = message; }
    public void stop() { state = State.STOPPED; }
    public void resume() {
        if (state != State.PAUSED) throw new IllegalStateException("lodgen.command.error.notPaused");
        state = prefix == plan.batches() ? State.COMPLETE : State.RUNNING; error = ""; cursor = prefix;
    }
    public State state() { return state; }
    public boolean completedArea(dev.ricky12awesome.lodgen.generation.GenerationArea area) { return plan.completedArea(prefix, area); }
    public String error() { return error; }
    public long completedChunks() {
        long result = plan.chunksBefore(prefix);
        for (long index : beyond) result += plan.batch(index).chunks();
        return result;
    }
    public Snapshot snapshot() { return new Snapshot(state, prefix, new ArrayList<>(beyond), error); }
}
