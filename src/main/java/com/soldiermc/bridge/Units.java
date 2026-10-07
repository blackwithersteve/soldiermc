package com.soldiermc.bridge;

import com.soldiermc.source.SourceFeel;

/** Engine: hu and hu/s; Minecraft: blocks and blocks per tick. */
public final class Units {

    /** Hammer units per Minecraft block. */
    public static final float S = SourceFeel.UNITS_PER_BLOCK;

    public static final float MC_TPS = 20.0f;

    public static double blocks(float hammerUnits) {
        return hammerUnits / S;
    }

    public static float hu(double blocks) {
        return (float) (blocks * S);
    }

    public static double velToMc(float huPerSecond) {
        return huPerSecond / S / MC_TPS;
    }

    public static float velToSource(double blocksPerTick) {
        return (float) (blocksPerTick * S * MC_TPS);
    }

    // mx = ex, my = ez, mz = -ey: a rotation; swapping y and z alone mirrors the world.
    public static double engineYToMcZ(float engineY) {
        return -blocks(engineY);
    }

    public static float mcZToEngineY(double mcZ) {
        return -hu(mcZ);
    }

    /** Minecraft yaw (0 = +Z, clockwise) to Source yaw (0 = +X, counterclockwise). */
    public static float sourceYaw(float mcYaw) {
        return -mcYaw - 90.0f;
    }

    private Units() {
    }
}
