package dev.ricky12awesome.lodgen.task;

import dev.ricky12awesome.lodgen.generation.GenerationArea;

public final class RadiusParser {
    private RadiusParser() {}
    public static int chunks(String text) {
        if (text == null || !text.matches("[0-9]+[cC]?")) throw new IllegalArgumentException("lodgen.command.error.invalid_radius");
        boolean chunkUnits = text.endsWith("c") || text.endsWith("C");
        long value;
        try { value = Long.parseLong(chunkUnits ? text.substring(0, text.length() - 1) : text); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("lodgen.command.error.radius_too_large"); }
        long chunks = chunkUnits ? value : value / 16 + (value % 16 == 0 ? 0 : 1);
        if (chunks > GenerationArea.MAX_RADIUS) throw new IllegalArgumentException("lodgen.command.error.radius_out_of_bounds");
        return (int) chunks;
    }
}
