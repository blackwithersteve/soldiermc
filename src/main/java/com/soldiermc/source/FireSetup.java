package com.soldiermc.source;

/** CTFWeaponBaseGun::FireRocket, tf_weaponbase_gun.cpp. */
public final class FireSetup {

    private final float[] basis = new float[9];
    private final float[] angles = new float[2];
    private final TraceResult trace = new TraceResult();

    /** Origin is the player's feet; pitch and yaw in degrees, positive pitch down. */
    public void fireRocket(WorldQuery world,
                           float originX, float originY, float originZ,
                           float pitch, float yaw, boolean ducked,
                           int shooterId, RocketSpawn out) {

        float offForward = SourceFeel.MUZZLE_OFFSET_FORWARD;
        float offRight = SourceFeel.MUZZLE_OFFSET_RIGHT;
        float offUp = ducked ? SourceFeel.MUZZLE_OFFSET_UP_DUCKED : SourceFeel.MUZZLE_OFFSET_UP_STANDING;

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

        world.traceLineSolid(trace, shooterId, eyeX, eyeY, eyeZ, endX, endY, endZ);
        float hitX = trace.endX, hitY = trace.endY, hitZ = trace.endZ;
        float fraction = trace.fraction;

        float srcX = eyeX + fx * offForward + rx * offRight + ux * offUp;
        float srcY = eyeY + fy * offForward + ry * offRight + uy * offUp;
        float srcZ = eyeZ + fz * offForward + rz * offRight + uz * offUp;
        out.muzzleX = srcX;
        out.muzzleY = srcY;
        out.muzzleZ = srcZ;

        // A hit within 200 hu is ignored and the rocket aims at the far point.
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

        // The pull-back moves the origin only; the angles still aim from the raw muzzle.
        world.traceLineBrush(trace, eyeX, eyeY, eyeZ, srcX, srcY, srcZ);
        out.originX = trace.endX;
        out.originY = trace.endY;
        out.originZ = trace.endZ;

        // Velocity is rebuilt from the angles, not the aim vector, to keep their rounding.
        AngleMath.forward(out.pitch, out.yaw, basis);
        out.velX = basis[0] * SourceFeel.ROCKET_SPEED;
        out.velY = basis[1] * SourceFeel.ROCKET_SPEED;
        out.velZ = basis[2] * SourceFeel.ROCKET_SPEED;
    }
}
