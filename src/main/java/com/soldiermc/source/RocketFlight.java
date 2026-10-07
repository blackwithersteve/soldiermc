package com.soldiermc.source;

/** CTFBaseRocket, tf_weaponbase_rocket.cpp: a point sweep that never hits the owner. */
public final class RocketFlight {

    private final TraceResult trace = new TraceResult();

    public float x, y, z;
    public float vx, vy, vz;
    public float damage;
    public boolean critical;
    public int shooterId;
    public boolean alive;

    public float blastX, blastY, blastZ;
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
}
