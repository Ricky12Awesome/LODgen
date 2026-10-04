package dev.lodgen.task;

import com.electronwill.nightconfig.core.CommentedConfig;
import dev.lodgen.generation.CaveMode;
import dev.lodgen.generation.GenerationArea;
import dev.lodgen.io.TomlFiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** World-local atomic task checkpoints. Active tasks resume; paused tasks stay paused. */
public final class TaskStore {
    private static final int LAYOUT_VERSION = 3;
    private TaskStore() {}
    public static TaskRecord read(Path path) throws IOException {
        if (!Files.exists(path)) return null;
        try {
            var data = TomlFiles.read(path);
            if (number(data.get("layoutVersion")) != LAYOUT_VERSION) throw new IllegalArgumentException("Unsupported task layout");
            var area = new GenerationArea(number(data.get("x")), number(data.get("z")), number(data.get("radius")), number(data.get("savedRadius")));
            List<?> values = data.get("completedBeyond");
            var completed = values.stream().map(TaskStore::integer).toList();
            var progress = new TaskProgress.Snapshot(TaskProgress.State.valueOf(data.get("state")), integer(data.get("completedPrefix")), completed, data.get("error"));
            new TaskProgress(new SquarePlan(area), progress); // Validate before a corrupt file can dispatch work.
            Object caveMode = data.get("caveMode");
            return new TaskRecord(data.get("dimension"), area, data.get("dh"), data.get("voxy"), data.get("automatic"), progress,
                    caveMode == null ? CaveMode.GENERATE : CaveMode.valueOf((String) caveMode));
        } catch (RuntimeException invalid) { throw new IOException("Invalid LODgen task checkpoint", invalid); }
    }
    private static int number(Object value) { return Math.toIntExact(integer(value)); }
    private static long integer(Object value) {
        if (!(value instanceof Integer || value instanceof Long)) throw new IllegalArgumentException("Expected TOML integer");
        return ((Number) value).longValue();
    }
    public static void write(Path path, TaskRecord task) throws IOException {
        var values = CommentedConfig.inMemory();
        values.set("layoutVersion", LAYOUT_VERSION);
        values.set("dimension", task.dimension());
        values.set("x", task.area().blockX()); values.set("z", task.area().blockZ());
        values.set("radius", task.area().radius()); values.set("savedRadius", task.area().savedRadius());
        values.set("dh", task.dh()); values.set("voxy", task.voxy());
        values.set("automatic", task.automatic());
        values.set("caveMode", task.caveMode().name());
        values.set("state", task.progress().state().name());
        values.set("completedPrefix", task.progress().prefix()); values.set("completedBeyond", task.progress().beyond());
        values.set("error", task.progress().error());
        TomlFiles.write(path, values);
    }
}
