package com.soldiermc.source;

/**
 * Source's {@code CMoveData} — the per-command scratch the movement pipeline reads and writes.
 * Float throughout, Hammer units, per-second velocities, angles in degrees. Mirrors the C++
 * field-for-field, with no vector class, because Source's arithmetic is per-component and the
 * rounding order matters.
 */
public final class MoveData {

    // ---- input for this command (written by the bridge, read by the engine) ----

    /** {@code m_flForwardMove}, hu/s. ±cl_forwardspeed / ±cl_backspeed before cropping. */
    public float forwardMove;
    /** {@code m_flSideMove}, hu/s. ±cl_sidespeed. */
    public float sideMove;
    /** {@code m_flUpMove}, hu/s. ±cl_upspeed. Unused by a Soldier. */
    public float upMove;

    /** {@code m_nButtons} — this command's held buttons. */
    public int buttons;
    /** {@code m_nOldButtons} — the previous command's, for edge detection. Survives across substeps. */
    public int oldButtons;
    /** {@code m_flOldForwardMove}. */
    public float oldForwardMove;

    /** {@code m_vecViewAngles}, degrees. Roll is always 0 in TF (sv_rollangle 0). */
    public float viewPitch;
    public float viewYaw;

    // ---- state carried across substeps ----

    /** {@code m_vecAbsOrigin}, hu. */
    public float originX, originY, originZ;
    /** {@code m_vecVelocity}, hu/s. */
    public float velX, velY, velZ;

    /**
     * {@code mv->m_flMaxSpeed} — the flat TF ceiling, set to {@link SourceFeel#TF_MAX_SPEED} each
     * command at tf_gamemovement.cpp:311.
     */
    public float maxSpeed;

    /**
     * {@code mv->m_flClientMaxSpeed} — the per-class cap (240 for a Soldier), which
     * {@code CheckParameters} MINs against. prediction.cpp:628.
     */
    public float clientMaxSpeed;

    /** {@code m_bGameCodeMovedPlayer} — forces a full CategorizePosition at the top of the substep. */
    public boolean gameCodeMovedPlayer;

    // ---- outputs the engine writes for view/animation telemetry only ----

    /** {@code m_outWishVel}. Telemetry; not read back by the simulation. */
    public float outWishVelX, outWishVelY, outWishVelZ;
    /** {@code m_outJumpVel}. Telemetry. */
    public float outJumpVelX, outJumpVelY, outJumpVelZ;

    /** 3D speed, hu/s. */
    public float speed() {
        return (float) Math.sqrt(velX * velX + velY * velY + velZ * velZ);
    }

    /** Horizontal speed, hu/s — what the instrument panel quotes against the 240 cap. */
    public float speed2D() {
        return (float) Math.sqrt(velX * velX + velY * velY);
    }
}
