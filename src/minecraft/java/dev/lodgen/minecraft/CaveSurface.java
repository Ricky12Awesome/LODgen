package dev.lodgen.minecraft;

/** The terrain floor before decoration, unaffected by tall trees and buildings. */
public interface CaveSurface {
    int[] lodgen$surfaceFloors();
    void lodgen$surfaceFloors(int[] floors);
    CaveMarkers lodgen$markers();
    void lodgen$markers(CaveMarkers markers);
}
