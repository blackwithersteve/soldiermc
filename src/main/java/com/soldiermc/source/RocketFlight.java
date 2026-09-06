package com.soldiermc.source;

/**
 * One rocket in flight: {@code CTFBaseRocket}'s sweep, touch and detonation point. A zero-size point
 * ({@code UTIL_SetSize(this, vec3_origin, vec3_origin)}, tf_weaponbase_rocket.cpp:159), so the sweep
 * is a ray, not a box, and it carries no gravity — not because of the {@code SetGravity(0.0f)} call
 * at :164, which is inert (a stored multiplier of exactly 0 is coerced back to 1.0 by
 * {@code GetActualGravity}), but because {@code MOVETYPE_FLY} is not {@code MOVETYPE_FLYGRAVITY}.
 * Owner exclusion falls out of the trace filter in Source ({@code PassServerEntityFilter} rejects
 * any entity that owns the tracing entity, util_shared.cpp:229) and is permanent, with no timer and
 * no arming distance: the muzzle sits 12.37 hu off the player's axis against a hull half-width of
 * 24, so the rocket spawns inside its own shooter. Teammate exclusion
 * ({@code GetCollideWithTeammatesDelay}, a 0.25 s expiry) is not implemented — one team here.
 */
public final class RocketFlight {

    private final TraceResult trace = new TraceResult();

    public float x, y, z;
    public float vx, vy, vz;
    public float damage;
    public boolean critical;
    public int shooterId;
    public boolean alive;

    /** Where it detonated, after the pull-back along the impact normal. */
    public float blastX, blastY, blastZ;
    /** The entity hit, or -1 for the world. A direct hit takes falloff distance 0. */
    public int hitEntityId = -1;

    public void launch(RocketSpawn s, int shooterId, float damage, boolean critical) {
        this.x = s.originX;
        this.y = s.originY;
        this.z = s.originZ;
        this.vx = s.velX;
        this.vy = s.velY;
        this.vz = s.velZ;
        this.shooterId = shooterId;
        this.damage = damage;
        this.critical = critical;
        this.alive = true;
        this.hitEntityId = -1;
    }

    /**
     * Advance one substep. No gravity, no drag, no steering: a straight line at a constant
     * 1100 hu/s.
     *
     * @return true if it detonated this step, in which case {@code blast*} is the explosion origin
     */
    public boolean step(WorldQuery world, float dt) {
        if (!alive) return false;

        float toX = x + vx * dt;
        float toY = y + vy * dt;
        float toZ = z + vz * dt;

        world.traceLineSolid(trace, shooterId, x, y, z, toX, toY, toZ);

        if (trace.fraction >= 1.0f) {
            x = toX;
            y = toY;
            z = toZ;
            return false;
        }

        // tf_weaponbase_rocket.cpp:433-437 — the detonation point is pulled 1 hu back along the
        // impact plane normal, and only when something was hit.
        blastX = trace.endX + trace.normalX * SourceFeel.EXPLOSION_PULLOUT;
        blastY = trace.endY + trace.normalY * SourceFeel.EXPLOSION_PULLOUT;
        blastZ = trace.endZ + trace.normalZ * SourceFeel.EXPLOSION_PULLOUT;

        hitEntityId = trace.hitEntityId;
        x = trace.endX;
        y = trace.endY;
        z = trace.endZ;
        alive = false;
        return true;
    }

    /** Distance travelled per substep at the stock speed, hu. */
    public static float stepDistance(float dt) {
        return SourceFeel.ROCKET_SPEED * dt;
    }
}
