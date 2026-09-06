package com.soldiermc.source;

/**
 * Crossing point 1, engine to world: the single interface through which the ported simulation learns
 * anything about the world it moves through. Hammer units both ways; the engine never sees a
 * {@code Level} or an {@code Entity}. Implemented by {@code com.soldiermc.bridge.McWorldQuery} and,
 * in tests, by an in-memory box world.
 */
public interface WorldQuery {

    /**
     * Sweep the player hull from {@code from} to {@code to}, Source's {@code TracePlayerBBox}. The
     * result must carry the impact plane normal, not just a clamped endpoint, because
     * {@code ClipVelocity} needs the normal to slide. It is written into a caller-owned
     * {@link TraceResult}, mirroring the by-reference {@code trace_t}: {@code TryPlayerMove} runs up
     * to four traces per substep at 66.67 Hz.
     *
     * @param out        filled in by the implementation; never null
     * @param hullHeight hull height in Hammer units; the caller picks standing or ducked
     */
    void trace(TraceResult out, float hullHeight,
               float fromX, float fromY, float fromZ,
               float toX, float toY, float toZ);

    /**
     * True if the hull placed at this origin overlaps solid geometry. Used by the stuck probe and by
     * {@code CanUnduck}.
     */
    boolean solidAt(float hullHeight, float x, float y, float z);

    /**
     * A zero-width line trace against world brushes only — {@code MASK_SOLID_BRUSHONLY},
     * bspflags.h:132. Two callers: the muzzle check, which pulls the rocket spawn back out of a wall
     * the player is standing against, and the blast's line-of-sight test, which must not be blocked
     * by the player it is testing. {@code MASK_RADIUS_DAMAGE} is
     * {@code MASK_SHOT & ~CONTENTS_HITBOX} (tf_gamerules.cpp:156), which collapses onto the same
     * query in a world whose only non-brush solids are players.
     */
    void traceLineBrush(TraceResult out,
                        float fromX, float fromY, float fromZ,
                        float toX, float toY, float toZ);

    /**
     * A zero-width line trace against brushes and entities — {@code MASK_SOLID}, bspflags.h:106 —
     * excluding the entity that owns the trace. The rocket's own sweep and the weapon's aim trace;
     * {@code hitEntityId} carries who was hit, or -1 for the world.
     *
     * @param ignoreEntityId the shooter, excluded for the life of the projectile
     */
    void traceLineSolid(TraceResult out, int ignoreEntityId,
                        float fromX, float fromY, float fromZ,
                        float toX, float toY, float toZ);
}
