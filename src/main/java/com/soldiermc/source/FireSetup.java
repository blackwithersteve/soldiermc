package com.soldiermc.source;

/**
 * {@code CTFWeaponBaseGun::FireRocket} plus {@code CTFWeaponBase::GetProjectileFireSetup}: the
 * player's eye position and view angles into a rocket origin and velocity. The aim trace starts at
 * the eye and runs before {@code vecSrc} is written, and the launch angle is computed from the raw
 * {@code vecSrc} before the eye-to-muzzle pull-back trace, which moves the origin only. The muzzle
 * sits 23.5 hu forward and 12 hu right of the eye, so the rocket is aimed from the muzzle at what
 * the eye trace found; below fraction 0.1 (200 hu at the default trace length) it falls back to the
 * uncorrected far point.
 */
public final class FireSetup {

    private final float[] basis = new float[9];
    private final float[] angles = new float[2];
    private final TraceResult trace = new TraceResult();

    /**
     * @param originX,originY,originZ the player's feet, hu
     * @param pitch,yaw               view angles, degrees, Source convention (positive pitch down)
     * @param shooterId               excluded from the aim trace and from the rocket's own sweep
     * @param out                     filled in; reused across shots
     */
    public void fireRocket(WorldQuery world,
                           float originX, float originY, float originZ,
                           float pitch, float yaw, boolean ducked,
                           int shooterId, RocketSpawn out) {

        // tf_weaponbase_gun.cpp:521-525. The ducked z is a hard REPLACEMENT of the component, not
        // a delta — and it changes sign, from -3 to +8.
        float offForward = SourceFeel.MUZZLE_OFFSET_FORWARD;
        float offRight = SourceFeel.MUZZLE_OFFSET_RIGHT;
        float offUp = ducked ? SourceFeel.MUZZLE_OFFSET_UP_DUCKED : SourceFeel.MUZZLE_OFFSET_UP_STANDING;

        // GetSpreadAngles() is EyeAngles() for the stock launcher: no punch angle, no spread, no RNG.
        AngleMath.angleVectors(pitch, yaw, 0f, basis);
        float fx = basis[0], fy = basis[1], fz = basis[2];
        float rx = basis[3], ry = basis[4], rz = basis[5];
        float ux = basis[6], uy = basis[7], uz = basis[8];

        float eyeZ = originZ + (ducked ? SourceFeel.VIEW_HEIGHT_DUCKED : SourceFeel.VIEW_HEIGHT_STANDING);
        float eyeX = originX;
        float eyeY = originY;

        float endX = eyeX + fx * SourceFeel.AIM_TRACE_DIST;
        float endY = eyeY + fy * SourceFeel.AIM_TRACE_DIST;
        float endZ = eyeZ + fz * SourceFeel.AIM_TRACE_DIST;

        // ORDER: the aim trace runs FROM THE EYE, before vecSrc exists. tf_weaponbase.cpp:5794.
        world.traceLineSolid(trace, shooterId, eyeX, eyeY, eyeZ, endX, endY, endZ);
        float hitX = trace.endX, hitY = trace.endY, hitZ = trace.endZ;
        float fraction = trace.fraction;

        // tf_weaponbase.cpp:5796 — the muzzle, in the eye-angle basis.
        float srcX = eyeX + fx * offForward + rx * offRight + ux * offUp;
        float srcY = eyeY + fy * offForward + ry * offRight + uy * offUp;
        float srcZ = eyeZ + fz * offForward + rz * offRight + uz * offUp;
        out.muzzleX = srcX;
        out.muzzleY = srcY;
        out.muzzleZ = srcZ;

        // tf_weaponbase.cpp:5801-5807. Above the gate, aim from the muzzle at what the eye trace
        // found; at or below it, aim at the far point and accept the parallax.
        float aimX, aimY, aimZ;
        if (fraction > SourceFeel.AIM_TRACE_MIN_FRACTION) {
            aimX = hitX - srcX;
            aimY = hitY - srcY;
            aimZ = hitZ - srcZ;
        } else {
            aimX = endX - srcX;
            aimY = endY - srcY;
            aimZ = endZ - srcZ;
        }
        AngleMath.vectorAngles(aimX, aimY, aimZ, angles);
        out.pitch = angles[0];
        out.yaw = angles[1];

        // tf_weaponbase_gun.cpp:531 — the pull-back, brushes only: the muzzle sits inside the
        // shooter's own hull. No startsolid guard here, unlike FirePipeBomb, so an eye already in
        // solid collapses the origin onto the eye and the rocket fires anyway.
        world.traceLineBrush(trace, eyeX, eyeY, eyeZ, srcX, srcY, srcZ);
        out.originX = trace.endX;
        out.originY = trace.endY;
        out.originZ = trace.endZ;

        // tf_weaponbase_rocket.cpp:272-313 — the velocity comes from the angles, not the aim vector,
        // so it inherits VectorAngles' rounding.
        AngleMath.forward(out.pitch, out.yaw, basis);
        out.velX = basis[0] * SourceFeel.ROCKET_SPEED;
        out.velY = basis[1] * SourceFeel.ROCKET_SPEED;
        out.velZ = basis[2] * SourceFeel.ROCKET_SPEED;
    }
}
