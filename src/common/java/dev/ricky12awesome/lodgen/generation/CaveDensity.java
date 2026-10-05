package dev.ricky12awesome.lodgen.generation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Conservative JSON density-tree rewrites for disposable LOD terrain. */
public final class CaveDensity {
    private CaveDensity() {}

    public record Result(JsonElement density, JsonElement terrain, int strippedSelectors, int emptyMasks) {
        public boolean supported() { return terrain != null; }
    }

    /** Removes only recognized vanilla cave selectors, preserving traversal and object field order. */
    public static Result transform(JsonElement density, boolean empty) {
        var state = new MutableResult();
        JsonElement stripped = stripCaves(density, state);
        if (state.terrain == null) return new Result(stripped, null, state.stripped, 0);
        JsonElement result = empty ? emptyShell(stripped, state.terrain, state) : stripped;
        return new Result(result, state.terrain, state.stripped, state.masks);
    }

    private static JsonElement emptyShell(JsonElement value, JsonElement terrain, MutableResult state) {
        if (!value.isJsonObject()) return value;
        var object = value.getAsJsonObject();
        var result = new JsonObject();
        boolean interpolate = object.has("type") && object.get("type").getAsString().equals("minecraft:interpolated");
        for (var entry : object.entrySet()) {
            if (interpolate && (entry.getKey().equals("argument") || entry.getKey().equals("input"))) {
                // Keep the cutoff inside native interpolation to retain its performance benefit.
                var shell = new JsonObject();
                shell.addProperty("type", "minecraft:range_choice");
                shell.add("input", terrain);
                shell.addProperty("min_inclusive", 4.0);
                shell.addProperty("max_exclusive", 1000000.0);
                shell.addProperty("when_in_range", -1.0);
                shell.add("when_out_of_range", entry.getValue());
                result.add(entry.getKey(), shell);
                state.masks++;
            } else result.add(entry.getKey(), emptyShell(entry.getValue(), terrain, state));
        }
        return result;
    }

    private static JsonElement stripCaves(JsonElement value, MutableResult state) {
        if (!value.isJsonObject()) return value;
        JsonObject object = value.getAsJsonObject();
        String type = object.has("type") ? object.get("type").getAsString() : "";
        if (type.equals("minecraft:range_choice") && object.has("input") && reference(object.get("input"), "sloped_cheese")) {
            state.stripped++;
            state.terrain = object.get("input");
            return object.get("input");
        }
        String left = object.has("left") ? "left" : "argument1";
        String right = object.has("right") ? "right" : "argument2";
        if (type.equals("minecraft:min") && object.has(right) && reference(object.get(right), "caves/noodle")) {
            state.stripped++;
            return stripCaves(object.get(left), state);
        }
        var result = new JsonObject();
        for (var entry : object.entrySet()) result.add(entry.getKey(), stripCaves(entry.getValue(), state));
        return result;
    }

    private static boolean reference(JsonElement value, String name) {
        // Cached terrain selectors occur in newer vanilla density trees.
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("type") && object.get("type").getAsString().equals("minecraft:cache_once")) {
                var argument = object.get("argument");
                if (argument == null) argument = object.get("input");
                return argument != null && reference(argument, name);
            }
        }
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && (value.getAsString().equals("minecraft:overworld/" + name)
                    || value.getAsString().equals("minecraft:overworld_large_biomes/" + name)
                    || value.getAsString().equals("minecraft:overworld_amplified/" + name));
    }

    private static final class MutableResult {
        JsonElement terrain;
        int stripped;
        int masks;
    }
}
