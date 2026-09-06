package com.soldiermc.source;

/**
 * Every constant that decides how the Soldier moves, in Source's own Hammer units. Each carries a
 * {@code file.cpp:LINE} citation into the Source SDK 2013 tree; these are
 * transcriptions, and {@link Unverified} holds the numbers that are not.
 */
public final class SourceFeel {

    // ---- the quantum ----

    /**
     * Simulation quantum, s. {@code DEFAULT_TICK_INTERVAL} (public/const.h:31) reached as
     * {@code TICK_INTERVAL} (shareddefs.h:14); fixed because {@code CTFGameMovement::ProcessMovement}
     * (tf_gamemovement.cpp:289) omits {@code GetLaggedMovementValue()} (gamemovement.cpp:1138).
     */
    public static final float DT = 0.015f;

    // ---- unit scale ----

    /** Hammer units per block: {@code 82 / 1.8 = 45.5556}, so the standing hull height converts exactly. */
    public static final float UNITS_PER_BLOCK = 45.5556f;

    // ---- engine ConVars ----

    /** {@code sv_gravity}, hu/s². movevars_shared.cpp:22 (define) and :37 (ConVar). */
    public static final float GRAVITY = 800.0f;

    /** {@code sv_friction}. movevars_shared.cpp:88. Ground only. */
    public static final float FRICTION = 4.0f;

    /** {@code sv_stopspeed}, hu/s. movevars_shared.cpp:42. The floor under the friction term. */
    public static final float STOPSPEED = 100.0f;

    /** {@code sv_accelerate}. movevars_shared.cpp:64. */
    public static final float ACCELERATE = 10.0f;

    /** {@code sv_airaccelerate}. movevars_shared.cpp:77. */
    public static final float AIR_ACCELERATE = 10.0f;

    /** {@code sv_maxvelocity}, hu/s. movevars_shared.cpp:99. Clamped per axis, gamemovement.cpp:3075. */
    public static final float MAX_VELOCITY = 3500.0f;

    /** {@code sv_bounce}. movevars_shared.cpp:98 (live twin). Zero — see {@link #OVERBOUNCE}. */
    public static final float BOUNCE = 0.0f;

    /** {@code sv_stepsize} as declared, hu. movevars_shared.cpp:100. The port uses {@link #STEP_SIZE_PORT}. */
    public static final float SV_STEPSIZE_SOURCE = 18.0f;

    /**
     * Step height the port actually uses, hu: Minecraft's {@code maxUpStep}, 0.6 blocks. Source's 18 hu
     * is 0.3951 blocks at this scale, below a slab, so a verbatim transcription would wall off terrain.
     */
    public static final float STEP_SIZE_PORT = 0.6f * UNITS_PER_BLOCK; // 27.3333 hu

    /** {@code sv_rollangle}. movevars_shared.cpp:82 (live twin). Zero, so roll is always 0 in TF. */
    public static final float ROLL_ANGLE = 0.0f;

    // ---- TF2 defines ----

    /**
     * {@code TF_MAX_SPEED}, hu/s. tf_gamemovement.cpp:85, set flat into {@code mv->m_flMaxSpeed} at :311;
     * the per-class cap arrives via {@code m_flClientMaxSpeed} and the MIN at gamemovement.cpp:997, not
     * via {@code GetPlayerMaxSpeed()}.
     */
    public static final float TF_MAX_SPEED = 520.0f;

    /**
     * Soldier max ground speed, hu/s. {@code scripts/playerclasses/soldier.ctx} key {@code speed_max},
     * ICE-encrypted inside {@code tf2_misc_dir.vpk} under key {@code E2NcUkG2}.
     */
    public static final float CLASS_MAX_SPEED = 240.0f;

    /** Soldier {@code health_max}, from the same decrypted class script. */
    public static final int CLASS_MAX_HEALTH = 200;

    /**
     * Jump impulse, hu/s. tf_gamemovement.cpp:1315, {@code (289.0f * flJumpMod) * flGroundFactor}; the
     * {@code sqrt(2*g*21)} derivation survives only as a comment at :1284. Applied with {@code +=} when
     * not ducking (:1334) and {@code =} when ducking (:1330), which discards existing upward velocity.
     */
    public static final float JUMP_IMPULSE = 289.0f;

    /**
     * {@code BUNNYJUMP_MAX_SPEED_FACTOR}, tf_gamemovement.cpp:1087. {@code PreventBunnyJumping} (:1092)
     * runs on every successful ground jump (:1258), scales the full 3D vector including z (:1110), and
     * caps against {@code player->m_flMaxspeed} (240 → 288), not {@code mv->m_flMaxSpeed} (520).
     */
    public static final float BUNNYJUMP_MAX_SPEED_FACTOR = 1.2f;

    /** Base air speed cap, hu/s. {@code GetAirSpeedCap}, gamemovement.h:104. Half of air strafing. */
    public static final float BASE_AIR_SPEED_CAP = 30.0f;

    /**
     * Velocity above which {@code CategorizePosition} detaches from ground without tracing, hu/s.
     * tf_gamemovement.cpp:2358, and the base fast path uses the same 250 (gamemovement.cpp:4612). Not
     * {@code NON_JUMP_VELOCITY} 140.0f (gamemovement.cpp:3830), which belongs to the
     * {@code CGameMovement::CategorizePosition} TF2 never runs.
     */
    public static final float GROUND_DETACH_SPEED = 250.0f;

    /**
     * {@code k_flMaxEntitySpeed}, baseentity_shared.cpp:75 = {@code k_flMaxVelocity * 2.0f}, with
     * {@code k_flMaxVelocity} 2000 at public/vphysics/performance.h:14. {@code CheckEntityVelocity}
     * (bes:1103) tests per-axis, rescales the whole vector, and discards above 100x.
     */
    public static final float MAX_ENTITY_SPEED = 4000.0f;

    /**
     * {@code JUMP_MIN_SPEED}, tf_player.cpp:151 — {@code sqrt(2 * g * 21)}, 268.328157. Not the jump
     * impulse ({@link #JUMP_IMPULSE}): it is the vertical floor in
     * {@code ApplyGenericPushbackImpulse} (tf_player.cpp:3390) for airblast and knockback.
     */
    public static final float JUMP_MIN_SPEED = 268.3281572999747f;

    /** {@code CTFWeaponBaseGun::SecondaryAttack} refire, tf_weaponbase_gun.cpp:250. */
    public static final float SECONDARY_REFIRE = 0.5f;

    /** {@code CBaseCombatWeapon::HandleFireOnEmpty}, basecombatweapon_shared.cpp:1836. */
    public static final float EMPTY_SOUND_INTERVAL = 0.5f;

    // ---- the rocket and its blast ----

    /** {@code TF_ROCKET_RADIUS}, tf_weaponbase_rocket.h:26 — the radius for damaging OTHERS. */
    public static final float BLAST_RADIUS = 146.0f;

    /**
     * {@code TF_ROCKET_RADIUS_FOR_RJS} = {@code 110.0f * 1.1f}, tf_weaponbase_rocket.h:25. Smaller than
     * the radius for others; {@code RadiusDamage} swaps it in for the attacker in a second pass.
     */
    public static final float SELF_BLAST_RADIUS = 121.0f;

    /**
     * Edge-of-radius damage as a fraction of the centre value. {@code DMG_HALF_FALLOFF}
     * (tf_shareddefs.cpp:739) selects a constant 0.5 at tf_gamerules.cpp:5766, and the self pass reuses
     * it rather than recomputing against the smaller radius.
     */
    public static final float FALLOFF_HALF = 0.5f;

    /**
     * {@code tf_damagescale_self_soldier}, tf_player.cpp:194, applied at :9167, gated on
     * {@code !FL_ONGROUND && !FL_INWATER}. It scales the push as well as the health loss, because
     * {@code SetDamageForForceCalc} is latched in {@code ApplyOnDamageModifyRules}
     * (tf_gamerules.cpp:5958) after this multiply — so the air:ground force ratio is 6:5, not 2:1.
     */
    public static final float SELF_DAMAGE_SCALE = 0.60f;

    /** {@code tf_damageforcescale_self_soldier_rj}, tf_player.cpp:191 — airborne, used at :10282. */
    public static final float FORCE_SCALE_SELF_RJ = 10.0f;

    /** {@code tf_damageforcescale_self_soldier_badrj}, tf_player.cpp:192 — grounded, used at :10278. */
    public static final float FORCE_SCALE_SELF_BADRJ = 5.0f;

    /** {@code DamageForce}'s numerator, tf_player.cpp:8665 — {@code 48*48*82}. */
    public static final float DAMAGE_FORCE_REFERENCE_VOLUME = 48f * 48f * 82f;

    /** {@code DamageForce}'s clamp, tf_player.cpp:8667 — applied BEFORE the self-push multiplier. */
    public static final float DAMAGE_FORCE_CLAMP = 1000.0f;

    /**
     * Blast origin lowered by this before the push direction is normalised. tf_player.cpp:10564,
     * {@code vecDir = inflictorWSC - Vector(0,0,10) - playerWSC} — subtracted from the blast, not added
     * to the player, and applied before normalising.
     */
    public static final float DAMAGE_DIR_Z_BIAS = 10.0f;

    /** Standing hull height, {@code VEC_HULL_MAX.z}, tf_gamerules.cpp:1345. */
    public static final float HULL_Z_STANDING = 82.0f;

    /** Ducked hull height, {@code VEC_DUCK_HULL_MAX.z}, tf_gamerules.cpp:1348. */
    public static final float HULL_Z_DUCKED = 62.0f;

    /**
     * {@code ApplyPushFromDamage} overrides the ducked hull z with 55, not 62 — tf_player.cpp:10260, via
     * an exact {@code ==} test on the duck hull size. A crouched blast therefore pushes
     * {@code 82/55 = 1.4909090909} times harder, where the real 62 would give 1.3226.
     */
    public static final float DUCK_FORCE_HULL_Z = 55.0f;

    /** {@code flLaunchSpeed}, tf_weaponbase_rocket.cpp:274. Hardcoded in C++, not in the script. */
    public static final float ROCKET_SPEED = 1100.0f;

    /**
     * Detonation point pulled back this far along the impact plane normal.
     * tf_weaponbase_rocket.cpp:433-437, conditional on {@code trace.fraction != 1.0}.
     */
    public static final float EXPLOSION_PULLOUT = 1.0f;

    /** Muzzle offset in the EYE-ANGLE basis: forward, RIGHT, up. tf_weaponbase_gun.cpp:521. */
    public static final float MUZZLE_OFFSET_FORWARD = 23.5f;
    public static final float MUZZLE_OFFSET_RIGHT = 12.0f;
    /** Standing. tf_weaponbase_gun.cpp:521. */
    public static final float MUZZLE_OFFSET_UP_STANDING = -3.0f;
    /** Ducking. A hard REPLACEMENT of the z component, not a delta. tf_weaponbase_gun.cpp:522-525. */
    public static final float MUZZLE_OFFSET_UP_DUCKED = 8.0f;

    /** {@code flEndDist} for the aim trace, tf_weaponbase.h:368. */
    public static final float AIM_TRACE_DIST = 2000.0f;

    /** The aim trace's short-hit gate, tf_weaponbase.cpp:5801 — 200 hu at the default distance. */
    public static final float AIM_TRACE_MIN_FRACTION = 0.1f;

    /**
     * {@code cl_pitchdown} / {@code cl_pitchup}, in_main.cpp:68-69, clamped at :743-749. FCVAR_CHEAT, so
     * ±90 is unreachable in TF2; Minecraft clamps to ±90, so the port must clamp here too.
     */
    public static final float PITCH_CLAMP = 89.0f;

    /** Minimum surface normal z for a plane to be standable. tf_gamemovement.cpp:2421 and :2426. */
    public static final float WALKABLE_NORMAL_Z = 0.7f;

    /**
     * {@code m_surfaceFriction} while airborne and rising below {@link #GROUND_DETACH_SPEED}.
     * tf_gamemovement.cpp:2433, inside the {@code normal[2] < 0.7f} branch (:2426) gated on
     * {@code vz > 0 && movetype != NOCLIP} (:2430-2431). It multiplies {@code accelspeed} in both
     * {@code Accelerate} and {@code AirAccelerate}, on the rise only — not blanket-airborne.
     */
    public static final float AIRBORNE_RISING_SURFACE_FRICTION = 0.25f;

    /** Default {@code m_surfaceFriction}: reset every categorize (tf_gamemovement.cpp:2341). */
    public static final float DEFAULT_SURFACE_FRICTION = 1.0f;

    /** {@code TIME_TO_DUCK}, s. shareddefs.h:106, TF-guarded at :105; :109 is the non-TF 0.4. */
    public static final float TIME_TO_DUCK = 0.2f;

    /** {@code TIME_TO_UNDUCK}, seconds. 0.2 in both builds. */
    public static final float TIME_TO_UNDUCK = 0.2f;

    /** {@code tf_clamp_back_speed}, tf_gamemovement.cpp:47. Backpedal cap: 0.9 x 240 = 216 hu/s. */
    public static final float TF_CLAMP_BACK_SPEED = 0.9f;

    /** {@code tf_clamp_back_speed_min}, hu/s. tf_gamemovement.cpp:48. Below this the clamp is skipped. */
    public static final float TF_CLAMP_BACK_SPEED_MIN = 100.0f;

    /** Duck speed crop factor, applied ground-only. gamemovement.cpp:4310. */
    public static final float DUCK_SPEED_CROP = 0.33333333f;

    // ---- input speeds ----

    /** {@code cl_forwardspeed}. in_main.cpp:78. */
    public static final float CL_FORWARDSPEED = 450.0f;
    /** {@code cl_backspeed}. in_main.cpp:79. */
    public static final float CL_BACKSPEED = 450.0f;
    /** {@code cl_sidespeed}. in_main.cpp:76. */
    public static final float CL_SIDESPEED = 450.0f;
    /** {@code cl_upspeed}. in_main.cpp:77. Unused by a Soldier. */
    public static final float CL_UPSPEED = 320.0f;

    // ---- collision ----

    /**
     * {@code DIST_EPSILON}, hu. public/coordsize.h:35 — the engine trace backs its endpoint off the
     * impact plane, so the hull stops short of a surface rather than flush against it. Without it float
     * interpolation lands the hull a fraction of a ULP past the plane and Minecraft's
     * {@code AABB.collideX} guard ({@code box.maxX <= shape.minX}) drops that block entirely in that
     * direction, with onset scaling linearly with distance from the world origin.
     */
    public static final float DIST_EPSILON = 0.03125f;

    /** {@code numbumps} in TryPlayerMove. gamemovement.cpp:2573. */
    public static final int NUM_BUMPS = 4;

    /** {@code MAX_CLIP_PLANES}. gamemovement.cpp — unreachable in practice, kept as an assertion. */
    public static final int MAX_CLIP_PLANES = 5;

    /** {@code 1.0 + sv_bounce * (1 - m_surfaceFriction)}, gamemovement.cpp:2731; sv_bounce is 0, so 1.0. */
    public static final float OVERBOUNCE = 1.0f;

    // ---- hulls and eye ----

    /** Standing hull height, hu. TF2's hull is 49×49×82; {@link #UNITS_PER_BLOCK} makes it exactly 1.8 b. */
    public static final float HULL_HEIGHT_STANDING = 82.0f;

    /** Ducked hull height, hu. TF2's ducked hull is 49×49×62. */
    public static final float HULL_HEIGHT_DUCKED = 62.0f;

    /** TF2 player hull width, hu. Not used as the collision probe; Minecraft's 0.6 b AABB is. */
    public static final float HULL_WIDTH = 49.0f;

    /** Standing eye height, hu. {@code Vector(0, 0, 68)} in {@code g_TFViewVectors}, tf_gamerules.cpp:1364. */
    public static final float VIEW_HEIGHT_STANDING = 68.0f;

    /** Ducked eye height, hu. {@code VEC_DUCK_VIEW}, same table. */
    public static final float VIEW_HEIGHT_DUCKED = 45.0f;

    /**
     * Airborne origin shift in {@code FinishDuck}/{@code FinishUnDuck}, hu. gamemovement.cpp:4235-4245
     * ({@code viewDelta} :4239, applied :4241-4242), {@code 82 - 62 = 20}. It is the crouch-jump lift.
     */
    public static final float DUCK_AIRBORNE_ORIGIN_SHIFT = HULL_HEIGHT_STANDING - HULL_HEIGHT_DUCKED;

    // ---- conversions ----

    /** Hammer units → Minecraft blocks. */
    public static float toBlocks(float hammerUnits) {
        return hammerUnits / UNITS_PER_BLOCK;
    }

    /** Minecraft blocks → Hammer units. */
    public static float toUnits(double blocks) {
        return (float) (blocks * UNITS_PER_BLOCK);
    }

    private SourceFeel() {
    }
}
