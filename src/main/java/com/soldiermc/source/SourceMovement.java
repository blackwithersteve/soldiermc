package com.soldiermc.source;

import static com.soldiermc.source.SourceFeel.*;

/** CTFGameMovement over CGameMovement, tf_gamemovement.cpp. */
public final class SourceMovement {

    private final WorldQuery world;
    private final TraceResult trace = new TraceResult();

    private boolean onGround;

    /** Set at the end of a substep and read by the next, so always one substep stale. */
    private float surfaceFriction = DEFAULT_SURFACE_FRICTION;

    private boolean ducked;
    private boolean ducking;
    /** Milliseconds, as in Source. */
    private float duckTime;

    /** Positive downward. */
    private float fallVelocity;

    private int speedCropped;
    private static final int SPEED_CROPPED_DUCK = 1;

    private float fwdX, fwdY, fwdZ;
    private float rightX, rightY, rightZ;

    public SourceMovement(WorldQuery world) {
        this.world = world;
    }

    public void processMovement(MoveData mv) {
        speedCropped = 0;
        mv.maxSpeed = TF_MAX_SPEED;

        playerMove(mv);

        mv.oldButtons = mv.buttons;
        mv.oldForwardMove = mv.forwardMove;
    }

    private void playerMove(MoveData mv) {
        checkParameters(mv);
        mv.outWishVelX = mv.outWishVelY = mv.outWishVelZ = 0f;
        mv.outJumpVelX = mv.outJumpVelY = mv.outJumpVelZ = 0f;

        reduceTimers();
        angleVectors(mv.viewPitch, mv.viewYaw);

        // sv_optimizedmovement fast path: no ground trace here, only the 250 hu/s detach.
        if (mv.gameCodeMovedPlayer) {
            categorizePosition(mv);
        } else if (mv.velZ > GROUND_DETACH_SPEED) {
            onGround = false;
        }

        if (!onGround) {
            fallVelocity = -mv.velZ;
        }

        duck(mv);

        fullWalkMove(mv);
    }

    private void fullWalkMove(MoveData mv) {
        startGravity(mv);

        if ((mv.buttons & Buttons.IN_JUMP) != 0) {
            checkJumpButton(mv);
        } else {
            mv.oldButtons &= ~Buttons.IN_JUMP;
        }

        checkVelocity(mv);

        if (onGround) {
            mv.velZ = 0f;
            friction(mv);
            walkMove(mv);
        } else {
            airMove(mv);
        }

        categorizePosition(mv);
        finishGravity(mv);

        if (onGround) {
            mv.velZ = 0f;
        }

        checkFalling();
        checkVelocity(mv);
    }

    /** Source's float multiply order: g*0.5*dt here, g*dt*0.5 in finishGravity. */
    private void startGravity(MoveData mv) {
        mv.velZ -= 1.0f * GRAVITY * 0.5f * DT;
    }

    private void finishGravity(MoveData mv) {
        mv.velZ -= 1.0f * GRAVITY * DT * 0.5f;
    }

    private void checkParameters(MoveData mv) {
        float spd = mv.forwardMove * mv.forwardMove
                  + mv.sideMove * mv.sideMove
                  + mv.upMove * mv.upMove;

        float maxspeed = mv.clientMaxSpeed;
        if (maxspeed != 0.0f) {
            mv.maxSpeed = Math.min(maxspeed, mv.maxSpeed);
        }

        if (spd != 0.0f && spd > mv.maxSpeed * mv.maxSpeed) {
            float ratio = mv.maxSpeed / (float) Math.sqrt(spd);
            mv.forwardMove *= ratio;
            mv.sideMove *= ratio;
            mv.upMove *= ratio;
        }
    }

    private void reduceTimers() {
        float ms = DT * 1000.0f;
        if (duckTime > 0f) {
            duckTime -= ms;
            if (duckTime < 0f) duckTime = 0f;
        }
    }

    /** Source's +Y is left, so right is (sin yaw, -cos yaw, 0) at roll 0. */
    private void angleVectors(float pitchDeg, float yawDeg) {
        double p = Math.toRadians(pitchDeg);
        double y = Math.toRadians(yawDeg);
        float cp = (float) Math.cos(p), sp = (float) Math.sin(p);
        float cy = (float) Math.cos(y), sy = (float) Math.sin(y);

        fwdX = cp * cy;
        fwdY = cp * sy;
        fwdZ = -sp;

        rightX = sy;
        rightY = -cy;
        rightZ = 0f;
    }

    private void friction(MoveData mv) {
        float speed = mv.speed();
        if (speed < 0.1f) return;

        float drop = 0f;
        if (onGround) {
            float control = (speed < STOPSPEED) ? STOPSPEED : speed;
            drop += control * FRICTION * DT * surfaceFriction;
        }

        float newspeed = speed - drop;
        if (newspeed < 0f) newspeed = 0f;

        if (newspeed != speed) {
            newspeed /= speed;
            mv.velX *= newspeed;
            mv.velY *= newspeed;
            mv.velZ *= newspeed;
        }
    }

    private void accelerate(MoveData mv, float wx, float wy, float wz, float wishspeed, float accel) {
        float currentspeed = mv.velX * wx + mv.velY * wy + mv.velZ * wz;
        float addspeed = wishspeed - currentspeed;
        if (addspeed <= 0f) return;

        float accelspeed = accel * DT * wishspeed * surfaceFriction;
        if (accelspeed > addspeed) accelspeed = addspeed;

        mv.velX += accelspeed * wx;
        mv.velY += accelspeed * wy;
        mv.velZ += accelspeed * wz;
    }

    private void airAccelerate(MoveData mv, float wx, float wy, float wz, float wishspeed, float accel) {
        float wishspd = wishspeed;

        float cap = getAirSpeedCap();
        if (wishspd > cap) wishspd = cap;

        float currentspeed = mv.velX * wx + mv.velY * wy + mv.velZ * wz;
        float addspeed = wishspd - currentspeed;
        if (addspeed <= 0f) return;

        // The uncapped wishspeed on purpose: this is what makes air strafing work.
        float accelspeed = accel * wishspeed * DT * surfaceFriction;
        if (accelspeed > addspeed) accelspeed = addspeed;

        mv.velX += accelspeed * wx;
        mv.velY += accelspeed * wy;
        mv.velZ += accelspeed * wz;
    }

    private float getAirSpeedCap() {
        return BASE_AIR_SPEED_CAP;
    }

    private float[] buildWish(MoveData mv) {
        float fx = fwdX, fy = fwdY;
        float len = (float) Math.sqrt(fx * fx + fy * fy);
        if (len > 1.0e-6f) {
            fx /= len;
            fy /= len;
        } else {
            fx = 0f;
            fy = 0f;
        }

        float wvx = fx * mv.forwardMove + rightX * mv.sideMove;
        float wvy = fy * mv.forwardMove + rightY * mv.sideMove;

        float wishspeed = (float) Math.sqrt(wvx * wvx + wvy * wvy);
        float wx = 0f, wy = 0f;
        if (wishspeed > 0f) {
            wx = wvx / wishspeed;
            wy = wvy / wishspeed;
        }

        if (wishspeed != 0f && wishspeed > mv.maxSpeed) {
            wishspeed = mv.maxSpeed;
        }
        // fx, fy: the planar forward for clampBackSpeed; fwdX, fwdY still carry cos(pitch).
        return new float[]{wx, wy, wishspeed, fx, fy};
    }

    private void walkMove(MoveData mv) {
        float[] w = buildWish(mv);
        float wx = w[0], wy = w[1], wishspeed = w[2];

        mv.velZ = 0f;
        accelerate(mv, wx, wy, 0f, wishspeed, ACCELERATE);
        mv.velZ = 0f;

        float flNewSpeed = mv.speed();
        if (flNewSpeed > mv.maxSpeed) {
            float flScale = mv.maxSpeed / flNewSpeed;
            mv.velX *= flScale;
            mv.velY *= flScale;
        }

        clampBackSpeed(mv, w[3], w[4]);

        float destX = mv.originX + mv.velX * DT;
        float destY = mv.originY + mv.velY * DT;

        world.trace(trace, hullHeight(), mv.originX, mv.originY, mv.originZ, destX, destY, mv.originZ);
        if (trace.fraction == 1.0f) {
            mv.originX = destX;
            mv.originY = destY;
            return;
        }
        stepMove(mv, destX, destY, mv.originZ);
    }

    private void clampBackSpeed(MoveData mv, float fx, float fy) {
        if (mv.speed2D() <= TF_CLAMP_BACK_SPEED_MIN) return;

        float flDot = mv.velX * fx + mv.velY * fy;
        if (flDot >= 0f) return;

        float flMaxBackSpeed = mv.maxSpeed * TF_CLAMP_BACK_SPEED;
        if (-flDot <= flMaxBackSpeed) return;

        float backX = fx * flDot, backY = fy * flDot;
        float rightPartX = mv.velX - backX, rightPartY = mv.velY - backY;

        float scale = flMaxBackSpeed / -flDot;
        backX *= scale;
        backY *= scale;

        mv.velX = backX + rightPartX;
        mv.velY = backY + rightPartY;

        float sp = mv.speed2D();
        if (sp > mv.maxSpeed) {
            float s = mv.maxSpeed / sp;
            mv.velX *= s;
            mv.velY *= s;
        }
    }

    private void airMove(MoveData mv) {
        float[] w = buildWish(mv);
        airAccelerate(mv, w[0], w[1], 0f, w[2], AIR_ACCELERATE);
        tryPlayerMove(mv);
    }

    private boolean checkJumpButton(MoveData mv) {
        // Ducked refuses before the pogo gate and the air latch, as in Source.
        if (ducked) return false;
        if (ducking && ducked) return false;
        if ((mv.oldButtons & Buttons.IN_JUMP) != 0) return false;

        if (!onGround) {
            mv.oldButtons |= Buttons.IN_JUMP;
            return false;
        }

        preventBunnyJumping(mv);
        onGround = false;

        float groundFactor = 1.0f;
        float jumpMod = 1.0f;
        float flMul = (JUMP_IMPULSE * jumpMod) * groundFactor;

        if (ducking || ducked) {
            mv.velZ = flMul;
        } else {
            mv.velZ += flMul;
        }

        // Applied again at the end of the move, as in Source: launch is 271 hu/s, not 277.
        finishGravity(mv);

        mv.oldButtons |= Buttons.IN_JUMP;
        return true;
    }

    /** Caps the full 3D speed, z included, at 1.2 x the class speed (288), not 520. */
    private void preventBunnyJumping(MoveData mv) {
        float maxScaled = BUNNYJUMP_MAX_SPEED_FACTOR * mv.clientMaxSpeed;
        if (maxScaled <= 0f) return;

        float spd = mv.speed();
        if (spd <= maxScaled) return;

        float f = maxScaled / spd;
        mv.velX *= f;
        mv.velY *= f;
        mv.velZ *= f;
    }

    /** Runs between the two gravity halves, so the vz it tests is mid-substep. */
    private void categorizePosition(MoveData mv) {
        surfaceFriction = DEFAULT_SURFACE_FRICTION;

        if (mv.velZ > GROUND_DETACH_SPEED) {
            onGround = false;
            return;
        }

        boolean moveToEndPos = onGround;
        float probe = 2.0f + (moveToEndPos ? STEP_SIZE_PORT : 0f);
        world.trace(trace, hullHeight(), mv.originX, mv.originY, mv.originZ,
                    mv.originX, mv.originY, mv.originZ - probe);

        boolean standable = trace.fraction < 1.0f && trace.normalZ >= WALKABLE_NORMAL_Z;

        if (!standable) {
            onGround = false;
            if (mv.velZ > 0f) {
                surfaceFriction = AIRBORNE_RISING_SURFACE_FRICTION;
            }
            return;
        }

        boolean wasAirborne = !onGround;
        onGround = true;
        surfaceFriction = DEFAULT_SURFACE_FRICTION;

        if (wasAirborne) {
            mv.velZ = 0f;
        }

        // StayOnGround, folded in; it never snaps on the landing substep.
        if (moveToEndPos && !trace.startSolid && trace.fraction > 0f && trace.fraction < 1.0f) {
            mv.originZ = trace.endZ;
        }
    }

    private void checkFalling() {
        if (onGround) {
            fallVelocity = 0f;
        }
    }

    /** Clamps per axis, not by magnitude, as Source does. */
    private void checkVelocity(MoveData mv) {
        mv.velX = clampAxis(mv.velX);
        mv.velY = clampAxis(mv.velY);
        mv.velZ = clampAxis(mv.velZ);
    }

    private static float clampAxis(float v) {
        if (Float.isNaN(v)) return 0f;
        if (v > MAX_VELOCITY) return MAX_VELOCITY;
        if (v < -MAX_VELOCITY) return -MAX_VELOCITY;
        return v;
    }

    private int tryPlayerMove(MoveData mv) {
        int blocked = 0;
        int numplanes = 0;
        float[] planes = new float[MAX_CLIP_PLANES * 3];

        float timeLeft = DT;

        float primalX = mv.velX, primalY = mv.velY, primalZ = mv.velZ;
        float origX = mv.velX, origY = mv.velY, origZ = mv.velZ;

        for (int bump = 0; bump < NUM_BUMPS; bump++) {
            if (mv.velX == 0f && mv.velY == 0f && mv.velZ == 0f) break;

            float endX = mv.originX + mv.velX * timeLeft;
            float endY = mv.originY + mv.velY * timeLeft;
            float endZ = mv.originZ + mv.velZ * timeLeft;

            world.trace(trace, hullHeight(), mv.originX, mv.originY, mv.originZ, endX, endY, endZ);

            if (trace.allSolid) {
                mv.velX = mv.velY = mv.velZ = 0f;
                return 4;
            }

            // Partial stuck probe: Source's stuck.fraction != 1 case needs a swept query.
            if (trace.fraction == 1.0f && world.solidAt(hullHeight(), endX, endY, endZ)) {
                mv.velX = mv.velY = mv.velZ = 0f;
                break;
            }

            if (trace.fraction > 0f) {
                // The swept prefix of the ray, not trace.endX/Y/Z.
                mv.originX += (endX - mv.originX) * trace.fraction;
                mv.originY += (endY - mv.originY) * trace.fraction;
                mv.originZ += (endZ - mv.originZ) * trace.fraction;
                origX = mv.velX;
                origY = mv.velY;
                origZ = mv.velZ;
                numplanes = 0;
            }

            if (trace.fraction == 1.0f) break;

            if (trace.normalZ > WALKABLE_NORMAL_Z) blocked |= 1;
            if (trace.normalZ == 0f) blocked |= 2;

            timeLeft -= timeLeft * trace.fraction;

            if (numplanes >= MAX_CLIP_PLANES) {
                mv.velX = mv.velY = mv.velZ = 0f;
                break;
            }

            planes[numplanes * 3] = trace.normalX;
            planes[numplanes * 3 + 1] = trace.normalY;
            planes[numplanes * 3 + 2] = trace.normalZ;
            numplanes++;

            if (numplanes == 1) {
                float[] c = clipVelocity(origX, origY, origZ,
                        planes[0], planes[1], planes[2], OVERBOUNCE);
                mv.velX = c[0];
                mv.velY = c[1];
                mv.velZ = c[2];
                origX = mv.velX;
                origY = mv.velY;
                origZ = mv.velZ;
            } else {
                int i;
                for (i = 0; i < numplanes; i++) {
                    float[] c = clipVelocity(mv.velX, mv.velY, mv.velZ,
                            planes[i * 3], planes[i * 3 + 1], planes[i * 3 + 2], OVERBOUNCE);
                    int j;
                    for (j = 0; j < numplanes; j++) {
                        if (j == i) continue;
                        float dot = c[0] * planes[j * 3] + c[1] * planes[j * 3 + 1] + c[2] * planes[j * 3 + 2];
                        if (dot < 0f) break;
                    }
                    if (j == numplanes) {
                        mv.velX = c[0];
                        mv.velY = c[1];
                        mv.velZ = c[2];
                        break;
                    }
                }

                if (i == numplanes) {
                    if (numplanes != 2) {
                        mv.velX = mv.velY = mv.velZ = 0f;
                        break;
                    }
                    float dirX = planes[1] * planes[5] - planes[2] * planes[4];
                    float dirY = planes[2] * planes[3] - planes[0] * planes[5];
                    float dirZ = planes[0] * planes[4] - planes[1] * planes[3];

                    float lenSq = dirX * dirX + dirY * dirY + dirZ * dirZ;
                    if (lenSq < Unverified.CREASE_DEGENERATE_EPSILON) {
                        mv.velX = mv.velY = mv.velZ = 0f;
                        break;
                    }
                    float inv = 1.0f / (float) Math.sqrt(lenSq);
                    dirX *= inv; dirY *= inv; dirZ *= inv;

                    float d = dirX * mv.velX + dirY * mv.velY + dirZ * mv.velZ;
                    mv.velX = dirX * d;
                    mv.velY = dirY * d;
                    mv.velZ = dirZ * d;
                }

                if (mv.velX * primalX + mv.velY * primalY + mv.velZ * primalZ <= 0f) {
                    mv.velX = mv.velY = mv.velZ = 0f;
                    break;
                }
            }
        }
        return blocked;
    }

    private float[] clipVelocity(float vx, float vy, float vz,
                                 float nx, float ny, float nz, float overbounce) {
        float backoff = (vx * nx + vy * ny + vz * nz) * overbounce;

        float outX = vx - nx * backoff;
        float outY = vy - ny * backoff;
        float outZ = vz - nz * backoff;

        float adjust = outX * nx + outY * ny + outZ * nz;
        if (adjust < 0.0f) {
            outX -= nx * adjust;
            outY -= ny * adjust;
            outZ -= nz * adjust;
        }
        return new float[]{outX, outY, outZ};
    }

    /** TF's order: the high road first, the low road only if it fails. */
    private void stepMove(MoveData mv, float destX, float destY, float destZ) {
        float startX = mv.originX, startY = mv.originY, startZ = mv.originZ;
        float velX = mv.velX, velY = mv.velY, velZ = mv.velZ;

        float step = STEP_SIZE_PORT + DIST_EPSILON;

        world.trace(trace, hullHeight(), startX, startY, startZ,
                    startX, startY, startZ + step);
        float upZ = trace.endZ;

        mv.originX = startX;
        mv.originY = startY;
        mv.originZ = upZ;
        mv.velX = velX;
        mv.velY = velY;
        mv.velZ = velZ;
        tryPlayerMove(mv);

        world.trace(trace, hullHeight(), mv.originX, mv.originY, mv.originZ,
                    mv.originX, mv.originY, mv.originZ - step);

        // Adopted before the test, as Valve does, so the equality below sees the new z.
        if (!trace.startSolid && !trace.allSolid) {
            mv.originZ = trace.endZ;
        }

        boolean lowRoad = (trace.fraction != 1.0f && trace.normalZ < WALKABLE_NORMAL_Z)
                       || (mv.originX == startX && mv.originY == startY && mv.originZ == startZ);
        if (!lowRoad) return;

        mv.originX = startX;
        mv.originY = startY;
        mv.originZ = startZ;
        mv.velX = velX;
        mv.velY = velY;
        mv.velZ = velZ;
        tryPlayerMove(mv);
    }

    private void duck(MoveData mv) {
        boolean wantsDuck = (mv.buttons & Buttons.IN_DUCK) != 0;
        boolean pressed = wantsDuck && (mv.oldButtons & Buttons.IN_DUCK) == 0;
        boolean released = !wantsDuck && (mv.oldButtons & Buttons.IN_DUCK) != 0;
        boolean inAir = !onGround;

        handleDuckingSpeedCrop(mv);

        if (pressed) {
            duckTime = TIME_TO_DUCK * 1000.0f;
            ducking = true;
        }
        if (released) {
            if (ducked) {
                duckTime = TIME_TO_UNDUCK * 1000.0f;
            } else if (ducking) {
                float duckMs = TIME_TO_DUCK * 1000.0f;
                duckTime = ((duckMs - duckTime) / duckMs) * (TIME_TO_UNDUCK * 1000.0f);
            }
            ducking = true;
        }

        if (wantsDuck) {
            if (!ducked && (inAir || duckTime <= 0f)) {
                finishDuck(mv, inAir);
            }
        } else if (ducked || ducking) {
            // CanUnduck comes before the timer; reversed, a low ceiling leaves ducking stuck on.
            if (canUnduck(mv)) {
                if (inAir || duckTime <= 0f) {
                    finishUnDuck(mv, inAir);
                }
            } else {
                duckTime = TIME_TO_UNDUCK * 1000.0f;
                ducked = true;
                ducking = false;
            }
        }

        // FinishMove overwrites this, but Source writes it here as well.
        if (wantsDuck) {
            mv.oldButtons |= Buttons.IN_DUCK;
        } else {
            mv.oldButtons &= ~Buttons.IN_DUCK;
        }
    }

    private void handleDuckingSpeedCrop(MoveData mv) {
        if ((speedCropped & SPEED_CROPPED_DUCK) != 0) return;
        if (!(ducked && onGround)) return;

        mv.forwardMove *= DUCK_SPEED_CROP;
        mv.sideMove *= DUCK_SPEED_CROP;
        mv.upMove *= DUCK_SPEED_CROP;
        speedCropped |= SPEED_CROPPED_DUCK;
    }

    /** The airborne origin shift is the crouch-jump lift. */
    private void finishDuck(MoveData mv, boolean inAir) {
        ducked = true;
        ducking = false;
        if (inAir) {
            mv.originZ += DUCK_AIRBORNE_ORIGIN_SHIFT;
        }
    }

    private void finishUnDuck(MoveData mv, boolean inAir) {
        ducked = false;
        ducking = false;
        duckTime = 0f;
        if (inAir) {
            mv.originZ -= DUCK_AIRBORNE_ORIGIN_SHIFT;
        }
    }

    private boolean canUnduck(MoveData mv) {
        return !world.solidAt(HULL_HEIGHT_STANDING, mv.originX, mv.originY, mv.originZ);
    }

    private float hullHeight() {
        return ducked ? HULL_HEIGHT_DUCKED : HULL_HEIGHT_STANDING;
    }

    public boolean onGround() {
        return onGround;
    }

    public boolean ducked() {
        return ducked;
    }

    public float surfaceFriction() {
        return surfaceFriction;
    }

    public void reset(boolean grounded) {
        onGround = grounded;
        surfaceFriction = DEFAULT_SURFACE_FRICTION;
        ducked = false;
        ducking = false;
        duckTime = 0f;
        fallVelocity = 0f;
        speedCropped = 0;
    }

    /** Keeps onGround; a grounded impulse with vz <= 250 has its z zeroed next substep. */
    public void applyAbsVelocityImpulse(MoveData mv, float x, float y, float z) {
        if (x == 0f && y == 0f && z == 0f) return;

        // CheckEntityVelocity: tested per axis, rescaled as a vector, dropped above 100x.
        final float r = SourceFeel.MAX_ENTITY_SPEED;
        if (!(x > -r && x < r && y > -r && y < r && z > -r && z < r)) {
            float speed = (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
            if (speed >= r * 100f) return;
            float s = r / speed;
            x *= s;
            y *= s;
            z *= s;
        }

        mv.velX += x;
        mv.velY += y;
        mv.velZ += z;
    }

    /** Airblast and knockback; the self-blast goes through applyAbsVelocityImpulse. */
    public void applyGenericPushbackImpulse(MoveData mv, float x, float y, float z) {
        if (onGround && z < SourceFeel.JUMP_MIN_SPEED) z = SourceFeel.JUMP_MIN_SPEED;
        onGround = false;
        applyAbsVelocityImpulse(mv, x, y, z);
    }

    /** @deprecated use applyAbsVelocityImpulse. */
    @Deprecated
    public void addImpulse(MoveData mv, float x, float y, float z) {
        applyAbsVelocityImpulse(mv, x, y, z);
    }
}
