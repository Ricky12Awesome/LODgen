package dev.ricky12awesome.lodgen.task;

import dev.ricky12awesome.lodgen.generation.GenerationArea;

public final class RadiusParser {
    private RadiusParser() {}
    public static int chunks(String text) {
        if (text == null || !text.matches("[0-9]+[cC]?")) throw new IllegalArgumentException("Use a block radius or a chunk radius with c, such as 1024 or 64c");
        boolean chunkUnits = text.endsWith("c") || text.endsWith("C");
        long value;
        try { value = Long.parseLong(chunkUnits ? text.substring(0, text.length() - 1) : text); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Radius is too large"); }
        long chunks = chunkUnits ? value : value / 16 + (value % 16 == 0 ? 0 : 1);
        if (chunks > GenerationArea.MAX_RADIUS) throw new IllegalArgumentException("Radius exceeds Minecraft's world bounds");
        return (int) chunks;
    }
}
