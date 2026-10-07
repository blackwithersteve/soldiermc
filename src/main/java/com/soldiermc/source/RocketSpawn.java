package com.soldiermc.source;

/** Hammer units in Source axes: +X forward, +Y left, +Z up. */
public final class RocketSpawn {

    public float originX, originY, originZ;
    public float velX, velY, velZ;
    public float pitch, yaw;

    /** The muzzle before the pull-back trace; origin* is after it. */
    public float muzzleX, muzzleY, muzzleZ;
}
