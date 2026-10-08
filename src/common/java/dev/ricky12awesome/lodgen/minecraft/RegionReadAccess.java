package dev.ricky12awesome.lodgen.minecraft;

/** Read-only access must not create a region file merely to discover its absence. */
public interface RegionReadAccess {
    boolean lodgen$missingRegion(Object position);

    static boolean missing(Object storage, Object position) {
        return storage instanceof RegionReadAccess access && access.lodgen$missingRegion(position);
    }
}
