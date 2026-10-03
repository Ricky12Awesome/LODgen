package dev.lodgen.generation;

/** Applies only to disposable LOD terrain. Saved chunks always use GENERATE. */
public enum CaveMode {
    GENERATE, FILL, EMPTY;

    public CaveMode next() { return values()[(ordinal() + 1) % values().length]; }
}
