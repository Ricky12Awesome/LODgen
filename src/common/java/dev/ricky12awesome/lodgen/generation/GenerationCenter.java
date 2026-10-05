package dev.ricky12awesome.lodgen.generation;

/** Horizontal generation center. CUSTOM coordinates are expressed in blocks. */
public enum GenerationCenter {
    CURRENT, ORIGIN, CUSTOM;
    public GenerationCenter next() { return values()[(ordinal() + 1) % values().length]; }
}
