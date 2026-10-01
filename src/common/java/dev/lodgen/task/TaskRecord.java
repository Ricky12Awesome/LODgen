package dev.lodgen.task;

import dev.lodgen.generation.GenerationArea;

public record TaskRecord(String dimension, GenerationArea area, boolean dh, boolean voxy, TaskProgress.Snapshot progress) {
    public TaskRecord {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("Task dimension is missing");
        new SquarePlan(area);
    }
}
