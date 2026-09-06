package com.soldiermc.source;

/**
 * Source's {@code trace_t}, reduced to the fields the movement code reads. Mutable and reused, as
 * the C++ reuses one {@code trace_t}: {@code TryPlayerMove} runs up to four traces per substep.
 */
public final class TraceResult {

    /** {@code trace.fraction} — 0..1 along the requested sweep. 1.0 means unobstructed. */
    public float fraction;

    /** {@code trace.endpos}, hu. */
    public float endX, endY, endZ;

    /** {@code trace.plane.normal} — the impact plane. Only meaningful when {@code fraction < 1}. */
    public float normalX, normalY, normalZ;

    /** {@code trace.startsolid} — the hull was already inside geometry when the sweep began. */
    public boolean startSolid;

    /**
     * {@code trace.allsolid} — the entire sweep is inside geometry. {@code TryPlayerMove} returns
     * early with {@code blocked = 4} on this (gamemovement.cpp:2628).
     */
    public boolean allSolid;

    /**
     * The entity the sweep hit, or {@code -1} for the world or a miss. An {@code int} because the
     * engine never learns what an entity is; the bridge assigns the ids and resolves them back.
     */
    public int hitEntityId = -1;

    /** Reset to an unobstructed sweep ending at the requested point. */
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
