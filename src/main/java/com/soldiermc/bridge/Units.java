package com.soldiermc.bridge;

import com.soldiermc.source.SourceFeel;

/**
 * The only unit-conversion site in the mod. The engine works in Hammer units and per-second
 * velocities, Minecraft in blocks and per-tick deltas, and every crossing goes through here.
 */
public final class Units {

    /** Hammer units per Minecraft block. See {@link SourceFeel#UNITS_PER_BLOCK}. */
    public static final float S = SourceFeel.UNITS_PER_BLOCK;

    /** Minecraft ticks per second. */
    public static final float MC_TPS = 20.0f;

    public static double blocks(float hammerUnits) {
        return hammerUnits / S;
    }

    public static float hu(double blocks) {
        return (float) (blocks * S);
    }

    /**
     * Source velocity (hu/s) → Minecraft {@code deltaMovement} (blocks/tick). Only for handing
     * vanilla something plausible to look at; the simulation's own position is authoritative.
     */
    public static double velToMc(float huPerSecond) {
        return huPerSecond / S / MC_TPS;
    }

    /** Minecraft {@code deltaMovement} (blocks/tick) → Source velocity (hu/s). */
    public static float velToSource(double blocksPerTick) {
        return (float) (blocksPerTick * S * MC_TPS);
    }

    // Source is z-up and right-handed, Minecraft y-up. The naive
    //     (ex, ey, ez) -> (mx, my, mz) = (ex, ez, ey)
    // swaps two axes, which is a reflection (determinant -1): it flips handedness, so the engine's
    // "right" comes out as Minecraft's left, and it is self-consistent inside the engine so an
    // engine-only test still passes. Negating one axis makes the map a rotation instead:
    //     mx = ex,   my = ez,   mz = -ey
    // with the yaw flipped to match. Negating the strafe key would also work but leaves the world
    // mirrored, and TF2's rocket spawn is left/right asymmetric (tf_weaponbase_gun.cpp:521).

    /** Engine y (Source's +Y, which is LEFT) → Minecraft z. */
    public static double engineYToMcZ(float engineY) {
        return -blocks(engineY);
    }

    /** Minecraft z → engine y. */
    public static float mcZToEngineY(double mcZ) {
        return -hu(mcZ);
    }

    /**
     * Minecraft yaw (degrees, 0 = +Z, clockwise) → Source yaw (degrees, 0 = +X, counter-clockwise),
     * under the rotation above. Minecraft's forward is {@code (-sin, cos)} in (x, z) and Source's
     * is {@code (cos, sin)} in (x, y) = {@code (cos, -sin)} in (x, z), so {@code -mcYaw - 90}.
     */
    public static float sourceYaw(float mcYaw) {
        return -mcYaw - 90.0f;
    }

    private Units() {
    }
}
