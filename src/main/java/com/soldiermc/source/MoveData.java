package com.soldiermc.source;

/** CMoveData. Floats, Hammer units, velocities per second, angles in degrees. */
public final class MoveData {

    public float forwardMove;
    public float sideMove;
    public float upMove;

    public int buttons;
    public int oldButtons;
    public float oldForwardMove;

    public float viewPitch;
    public float viewYaw;

    public float originX, originY, originZ;
    public float velX, velY, velZ;

    /** Set to 520 each command, then lowered to clientMaxSpeed by checkParameters. */
    public float maxSpeed;

    public float clientMaxSpeed;

    public boolean gameCodeMovedPlayer;

    public float outWishVelX, outWishVelY, outWishVelZ;
    public float outJumpVelX, outJumpVelY, outJumpVelZ;

    public float speed() {
        return (float) Math.sqrt(velX * velX + velY * velY + velZ * velZ);
    }

    public float speed2D() {
        return (float) Math.sqrt(velX * velX + velY * velY);
    }
}
