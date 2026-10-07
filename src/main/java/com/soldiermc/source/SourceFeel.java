package com.soldiermc.source;

/** Constants transcribed from the Source SDK 2013, in Hammer units. */
public final class SourceFeel {

    public static final float DT = 0.015f;

    /** 82 / 1.8, so the 82 hu hull is exactly Minecraft's 1.8 block player. */
    public static final float UNITS_PER_BLOCK = 45.5556f;

    public static final float GRAVITY = 800.0f;
    public static final float FRICTION = 4.0f;
    public static final float STOPSPEED = 100.0f;
    public static final float ACCELERATE = 10.0f;
    public static final float AIR_ACCELERATE = 10.0f;
    public static final float MAX_VELOCITY = 3500.0f;
    /** Minecraft's 0.6 block step; Source's 18 hu would not climb a slab. */
    public static final float STEP_SIZE_PORT = 0.6f * UNITS_PER_BLOCK;

    public static final float TF_MAX_SPEED = 520.0f;
    public static final float CLASS_MAX_SPEED = 240.0f;
    public static final float JUMP_IMPULSE = 289.0f;
    public static final float BUNNYJUMP_MAX_SPEED_FACTOR = 1.2f;
    public static final float BASE_AIR_SPEED_CAP = 30.0f;

    /** 250, not NON_JUMP_VELOCITY's 140: TF's CategorizePosition uses its own. */
    public static final float GROUND_DETACH_SPEED = 250.0f;

    public static final float MAX_ENTITY_SPEED = 4000.0f;
    public static final float JUMP_MIN_SPEED = 268.3281572999747f;
    public static final float SECONDARY_REFIRE = 0.5f;
    public static final float EMPTY_SOUND_INTERVAL = 0.5f;
    public static final float SELF_BLAST_RADIUS = 121.0f;
    public static final float FALLOFF_HALF = 0.5f;
    public static final float SELF_DAMAGE_SCALE = 0.60f;
    public static final float FORCE_SCALE_SELF_RJ = 10.0f;
    public static final float FORCE_SCALE_SELF_BADRJ = 5.0f;
    public static final float DAMAGE_FORCE_REFERENCE_VOLUME = 48f * 48f * 82f;
    public static final float DAMAGE_FORCE_CLAMP = 1000.0f;
    public static final float DAMAGE_DIR_Z_BIAS = 10.0f;
    public static final float HULL_Z_STANDING = 82.0f;
    public static final float HULL_Z_DUCKED = 62.0f;

    /** ApplyPushFromDamage's ducked hull z: 55, not the real 62. */
    public static final float DUCK_FORCE_HULL_Z = 55.0f;

    public static final float ROCKET_SPEED = 1100.0f;
    public static final float EXPLOSION_PULLOUT = 1.0f;

    /** Eye-angle basis: forward, right (not Source's +Y left), up. */
    public static final float MUZZLE_OFFSET_FORWARD = 23.5f;
    public static final float MUZZLE_OFFSET_RIGHT = 12.0f;
    public static final float MUZZLE_OFFSET_UP_STANDING = -3.0f;
    public static final float MUZZLE_OFFSET_UP_DUCKED = 8.0f;

    public static final float AIM_TRACE_DIST = 2000.0f;
    public static final float AIM_TRACE_MIN_FRACTION = 0.1f;

    /** 89, not Minecraft's 90: cl_pitchdown and cl_pitchup. */
    public static final float PITCH_CLAMP = 89.0f;

    public static final float WALKABLE_NORMAL_Z = 0.7f;
    public static final float AIRBORNE_RISING_SURFACE_FRICTION = 0.25f;
    public static final float DEFAULT_SURFACE_FRICTION = 1.0f;

    /** TF's 0.2; the non-TF build uses 0.4. */
    public static final float TIME_TO_DUCK = 0.2f;

    public static final float TIME_TO_UNDUCK = 0.2f;
    public static final float TF_CLAMP_BACK_SPEED = 0.9f;
    public static final float TF_CLAMP_BACK_SPEED_MIN = 100.0f;
    public static final float DUCK_SPEED_CROP = 0.33333333f;

    public static final float CL_FORWARDSPEED = 450.0f;
    public static final float CL_BACKSPEED = 450.0f;
    public static final float CL_SIDESPEED = 450.0f;
    /** Trace back-off; flush contact lets Minecraft's AABB.collideX skip the block. */
    public static final float DIST_EPSILON = 0.03125f;

    public static final int NUM_BUMPS = 4;
    public static final int MAX_CLIP_PLANES = 5;
    public static final float OVERBOUNCE = 1.0f;

    public static final float HULL_HEIGHT_STANDING = 82.0f;
    public static final float HULL_HEIGHT_DUCKED = 62.0f;

    public static final float VIEW_HEIGHT_STANDING = 68.0f;
    public static final float VIEW_HEIGHT_DUCKED = 45.0f;
    public static final float DUCK_AIRBORNE_ORIGIN_SHIFT = HULL_HEIGHT_STANDING - HULL_HEIGHT_DUCKED;

    public static float toBlocks(float hammerUnits) {
        return hammerUnits / UNITS_PER_BLOCK;
    }

    public static float toUnits(double blocks) {
        return (float) (blocks * UNITS_PER_BLOCK);
    }

    private SourceFeel() {
    }
}
