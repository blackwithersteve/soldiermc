package com.soldiermc.source;

/** The engine's only view of the world; every coordinate is in Hammer units. */
public interface WorldQuery {

    /** Sweeps the player hull; out must carry the impact normal for ClipVelocity. */
    void trace(TraceResult out, float hullHeight,
               float fromX, float fromY, float fromZ,
               float toX, float toY, float toZ);

    boolean solidAt(float hullHeight, float x, float y, float z);

    /** Brushes only (MASK_SOLID_BRUSHONLY), so players never block it. */
    void traceLineBrush(TraceResult out,
                        float fromX, float fromY, float fromZ,
                        float toX, float toY, float toZ);

    /** Brushes and entities (MASK_SOLID); sets hitEntityId, or -1 for the world. */
    void traceLineSolid(TraceResult out, int ignoreEntityId,
                        float fromX, float fromY, float fromZ,
                        float toX, float toY, float toZ);
}
