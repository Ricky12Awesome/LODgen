package dev.ricky12awesome.lodgen.generation;

/** DH owns its surface/chunk phases; the addon toggle controls extra jobs. */
public record GenerationPolicy(boolean enabled, Plan plan, boolean features) {
    public enum Plan { SURFACE_THEN_CHUNKS, SURFACE_ONLY, CHUNKS_ONLY, DISABLED, NO_DH }
    public boolean automatic() { return enabled && plan != Plan.DISABLED; }
    public boolean dhChunks() {
        return features && (plan == Plan.SURFACE_THEN_CHUNKS || plan == Plan.CHUNKS_ONLY);
    }
    public boolean automaticChunks() { return automatic() && (features || plan == Plan.NO_DH); }
    public boolean surfaceFirst() { return plan == Plan.SURFACE_THEN_CHUNKS || plan == Plan.SURFACE_ONLY; }
    public boolean anyAutomatic() { return automatic() || plan != Plan.DISABLED && plan != Plan.NO_DH; }
}
