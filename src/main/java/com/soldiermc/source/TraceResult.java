package com.soldiermc.source;

/** trace_t, reduced to the fields the movement code reads. */
public final class TraceResult {

    public float fraction;
    public float endX, endY, endZ;
    public float normalX, normalY, normalZ;
    public boolean startSolid;
    public boolean allSolid;

    /** -1 for the world or a miss. */
    public int hitEntityId = -1;

    public void clear(float toX, float toY, float toZ) {
        fraction = 1.0f;
        hitEntityId = -1;
        endX = toX;
        endY = toY;
        endZ = toZ;
        normalX = 0f;
        normalY = 0f;
        normalZ = 0f;
        startSolid = false;
        allSolid = false;
    }
}
