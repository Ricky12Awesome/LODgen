package dev.lodgen.task;

import dev.lodgen.generation.GenerationArea;

public record TaskRecord(String dimension, GenerationArea area, boolean dh, boolean voxy, boolean automatic, TaskProgress.Snapshot progress, dev.lodgen.generation.CaveMode caveMode) {
    public TaskRecord(String dimension, GenerationArea area, boolean dh, boolean voxy, boolean automatic, TaskProgress.Snapshot progress) {
        this(dimension, area, dh, voxy, automatic, progress, dev.lodgen.generation.CaveMode.GENERATE);
    }
    public TaskRecord(String dimension, GenerationArea area, boolean dh, boolean voxy, TaskProgress.Snapshot progress) {
        this(dimension, area, dh, voxy, false, progress);
    }
    public TaskRecord {
        java.util.Objects.requireNonNull(caveMode, "caveMode");
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Task dimension is missing");
        new SquarePlan(area);
    }
}
