package com.soldiermc.source;

/**
 * Where a rocket starts and where it is going. Mutable and reused, never allocated per shot. Hammer
 * units, Source space (+X forward, +Y left, +Z up).
 */
public final class RocketSpawn {

    /** Spawn origin, after the eye-to-muzzle pull-back. */
    public float originX, originY, originZ;

    /** Velocity, hu/s. Already {@code forward * 1100}. */
    public float velX, velY, velZ;

    /** The launch angles the velocity was built from. Kept for the display model's orientation. */
    public float pitch, yaw;

    /** The raw muzzle position before the pull-back trace, for diagnostics. */
    public float muzzleX, muzzleY, muzzleZ;
}
