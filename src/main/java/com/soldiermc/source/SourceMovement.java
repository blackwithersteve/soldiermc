package com.soldiermc.source;

import static com.soldiermc.source.SourceFeel.*;

/**
 * {@code CTFGameMovement} over {@code CGameMovement}, tf_gamemovement.cpp. Imports {@code java.*}
 * only. {@code categorizePosition} runs at the end of a substep, so the {@code surfaceFriction} the
 * next one reads is stale; gravity is applied in halves; and {@code addspeed} uses the capped
 * wishspeed where {@code accelspeed} uses the uncapped one.
 */
public final class SourceMovement {

    private final WorldQuery world;
    private final TraceResult trace = new TraceResult();

    // ---- player state that outlives a substep ----

    /** {@code player->GetGroundEntity() != NULL}. */
    private boolean onGround;

    /**
     * {@code player->m_surfaceFriction}. Written by {@link #categorizePosition(MoveData)} at the END of
     * a substep and read by the NEXT one.
     */
    private float surfaceFriction = DEFAULT_SURFACE_FRICTION;

    /** {@code player->m_Local.m_bDucked} — the hull is currently the crouch hull. */
    private boolean ducked;
    /** {@code player->m_Local.m_bDucking} — mid-transition. */
    private boolean ducking;
    /** {@code player->m_Local.m_flDucktime}, ms. */
    private float duckTime;

    /** {@code player->m_flFallVelocity}, hu/s, positive downward. */
    private float fallVelocity;

    /** {@code m_iSpeedCropped} bitfield (gamemovement.h:30-32). */
    private int speedCropped;
    private static final int SPEED_CROPPED_DUCK = 1;

    // ---- per-substep basis vectors, from AngleVectors ----
    private float fwdX, fwdY, fwdZ;
    private float rightX, rightY, rightZ;

    public SourceMovement(WorldQuery world) {
        this.world = world;
    }

    // ---- CTFGameMovement::ProcessMovement, tf_gamemovement.cpp:289 ----

    /** Run exactly one 0.015 s substep. */
    public void processMovement(MoveData mv) {
        speedCropped = 0;                                   // tfgm:303
        mv.maxSpeed = TF_MAX_SPEED;                         // tfgm:311

        playerMove(mv);                                     // tfgm:329

        // FinishMove, gm:1197-1200 — via tfgm:332
        mv.oldButtons = mv.buttons;
        mv.oldForwardMove = mv.forwardMove;
    }

    /** {@code CGameMovement::PlayerMove}, gm:4570. */
    private void playerMove(MoveData mv) {
        checkParameters(mv);                                // gm:4574
        mv.outWishVelX = mv.outWishVelY = mv.outWishVelZ = 0f;   // gm:4577
        mv.outJumpVelX = mv.outJumpVelY = mv.outJumpVelZ = 0f;   // gm:4578

        reduceTimers();                                     // gm:4582
        angleVectors(mv.viewPitch, mv.viewYaw);             // gm:4584

        // checkStuck() is stubbed to 0, so the substep never aborts; the probe in tryPlayerMove covers it.

        // Optimized ground fast path, gm:4604-4615. sv_optimizedmovement defaults to 1 and movetype is
        // WALK, so the full categorize happens at the end of fullWalkMove instead of here.
        if (mv.gameCodeMovedPlayer) {
            categorizePosition(mv);                         // gm:4608
        } else if (mv.velZ > GROUND_DETACH_SPEED) {         // gm:4612 — same 250 as tfgm:2358
            onGround = false;
        }

        if (!onGround) {
            fallVelocity = -mv.velZ;                        // gm:4624
        }

        duck(mv);                                           // gm:4632 -> tfgm:3389

        fullWalkMove(mv);
    }

    // ---- CTFGameMovement::FullWalkMove, tf_gamemovement.cpp:2622 ----

    private void fullWalkMove(MoveData mv) {
        startGravity(mv);                                   // tfgm:2641

        if ((mv.buttons & Buttons.IN_JUMP) != 0) {
            checkJumpButton(mv);                            // tfgm:2665
        } else {
            mv.oldButtons &= ~Buttons.IN_JUMP;              // tfgm:2669 — the released-edge gate
        }

        checkVelocity(mv);                                  // tfgm:2673

        if (onGround) {
            mv.velZ = 0f;                                   // tfgm:2677 (zeroing 2 of 3)
            friction(mv);                                   // tfgm:2678
            walkMove(mv);                                   // tfgm:2679
        } else {
            airMove(mv);                                    // tfgm:2683
        }

        categorizePosition(mv);                             // tfgm:2687 — sets surfaceFriction for the next substep
        finishGravity(mv);                                  // tfgm:2692

        if (onGround) {
            mv.velZ = 0f;                                   // tfgm:2698 (zeroing 3 of 3)
        }

        checkFalling();                                     // tfgm:2702
        checkVelocity(mv);                                  // tfgm:2705
    }

    // ---- gravity: two halves of 6.0 hu/s straddling the move ----

    /** {@code StartGravity}, gm:1246. The multiply order at gm:1257 is {@code g * 0.5 * dt}. */
    private void startGravity(MoveData mv) {
        mv.velZ -= 1.0f * GRAVITY * 0.5f * DT;
    }

    /** {@code FinishGravity}, gm:1682. The multiply order at gm:1695 is {@code g * dt * 0.5}. */
    private void finishGravity(MoveData mv) {
        mv.velZ -= 1.0f * GRAVITY * DT * 0.5f;
    }

    // ---- CheckParameters, gm:981 ----

    private void checkParameters(MoveData mv) {
        float spd = mv.forwardMove * mv.forwardMove
                  + mv.sideMove * mv.sideMove
                  + mv.upMove * mv.upMove;

        // gm:996-999 writes the MIN back into mv->m_flMaxSpeed, so every later consumer — the ground
        // clamp in walkMove, the AirMove clamp — reads 240 rather than 520.
        float maxspeed = mv.clientMaxSpeed;                     // gm:996
        if (maxspeed != 0.0f) {
            mv.maxSpeed = Math.min(maxspeed, mv.maxSpeed);      // gm:999
        }

        if (spd != 0.0f && spd > mv.maxSpeed * mv.maxSpeed) {   // gm:1019
            float ratio = mv.maxSpeed / (float) Math.sqrt(spd);
            mv.forwardMove *= ratio;
            mv.sideMove *= ratio;
            mv.upMove *= ratio;
        }
    }

    /** {@code ReduceTimers}, gm:1088. Duck timer only — TF2 never writes m_flDuckJumpTime. */
    private void reduceTimers() {
        float ms = DT * 1000.0f;
        if (duckTime > 0f) {
            duckTime -= ms;
            if (duckTime < 0f) duckTime = 0f;
        }
    }

    /**
     * {@code AngleVectors}, mathlib_base.cpp:919, with roll fixed at 0. Source's +Y is LEFT:
     * {@code right = (sin(yaw), -cos(yaw), 0)} at roll 0 (mathlib_base.cpp:948-950), so a standard
     * right-handed cross product would mirror every strafe.
     */
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

    // ---- Friction, gm:1610. Ground only. ----

    private void friction(MoveData mv) {
        float speed = mv.speed();
        if (speed < 0.1f) return;                           // gm:1625

        float drop = 0f;
        if (onGround) {
            float control = (speed < STOPSPEED) ? STOPSPEED : speed;   // gm:1633
            drop += control * FRICTION * DT * surfaceFriction;         // gm:1636
        }

        float newspeed = speed - drop;                      // gm:1663
        if (newspeed < 0f) newspeed = 0f;

        if (newspeed != speed) {                            // gm:1668
            newspeed /= speed;                              // gm:1671 — reused as a RATIO
            mv.velX *= newspeed;
            mv.velY *= newspeed;
            mv.velZ *= newspeed;
        }

        // gm:1676 writes m_outWishVel here using that ratio. Telemetry only, not reproduced.
    }

    // ---- acceleration ----

    /** {@code Accelerate}, gm:1820. {@code addspeed} and {@code accelspeed} share one wishspeed. */
    private void accelerate(MoveData mv, float wx, float wy, float wz, float wishspeed, float accel) {
        float currentspeed = mv.velX * wx + mv.velY * wy + mv.velZ * wz;   // gm:1831
        float addspeed = wishspeed - currentspeed;                          // gm:1834
        if (addspeed <= 0f) return;                                         // gm:1837

        float accelspeed = accel * DT * wishspeed * surfaceFriction;        // gm:1841
        if (accelspeed > addspeed) accelspeed = addspeed;                   // gm:1844

        mv.velX += accelspeed * wx;                                         // gm:1848
        mv.velY += accelspeed * wy;
        mv.velZ += accelspeed * wz;
    }

    /**
     * {@code AirAccelerate}, gm:1705 — this function is air strafing. {@code addspeed} is measured
     * against {@code wishspd}, capped at {@link SourceFeel#BASE_AIR_SPEED_CAP} (gm:1720-1727), while
     * {@code accelspeed} is scaled by the uncapped {@code wishspeed} (gm:1734). Nothing here measures
     * total speed, so a wishdir held near-perpendicular to velocity keeps the full budget every substep.
     */
    private void airAccelerate(MoveData mv, float wx, float wy, float wz, float wishspeed, float accel) {
        float wishspd = wishspeed;                                          // gm:1711

        float cap = getAirSpeedCap();
        if (wishspd > cap) wishspd = cap;                                   // gm:1720-1721

        float currentspeed = mv.velX * wx + mv.velY * wy + mv.velZ * wz;    // gm:1724
        float addspeed = wishspd - currentspeed;                            // gm:1727  CAPPED
        if (addspeed <= 0f) return;                                         // gm:1730

        float accelspeed = accel * wishspeed * DT * surfaceFriction;        // gm:1734  UNCAPPED
        if (accelspeed > addspeed) accelspeed = addspeed;                   // gm:1737

        mv.velX += accelspeed * wx;                                         // gm:1743
        mv.velY += accelspeed * wy;
        mv.velZ += accelspeed * wz;
    }

    /**
     * {@code CTFGameMovement::GetAirSpeedCap}, tfgm:2033. A stock Soldier resolves to a flat 30: both
     * air-control attributes are 1.0 ({@code items_game.txt} grants {@code mod_air_control} to no item)
     * and the two hooks at :2083 and :2086 compose rather than overwrite. {@code tf_space_aircontrol}
     * has no ConVar definition in the release and both its call sites are commented out.
     */
    private float getAirSpeedCap() {
        return BASE_AIR_SPEED_CAP;                                          // tfgm:2057 -> :2094
    }

    // ---- WalkMove / AirMove ----

    /**
     * Build {@code wishdir} and {@code wishspeed} from the command, shared by walk and air. Forward has
     * its z zeroed and is then renormalised (tfgm:2131-2134); without the renormalise wishspeed shrinks
     * by cos(pitch) and divides by ~0 at |pitch| = 90.
     *
     * @return {@code {wishX, wishY, wishspeed}}
     */
    private float[] buildWish(MoveData mv) {
        float fx = fwdX, fy = fwdY;
        float len = (float) Math.sqrt(fx * fx + fy * fy);
        if (len > 1.0e-6f) {                                // guard |pitch| == 90
            fx /= len;
            fy /= len;
        } else {
            fx = 0f;
            fy = 0f;
        }

        float wvx = fx * mv.forwardMove + rightX * mv.sideMove;   // tfgm:2136
        float wvy = fy * mv.forwardMove + rightY * mv.sideMove;   // tfgm:2137

        float wishspeed = (float) Math.sqrt(wvx * wvx + wvy * wvy);   // tfgm:2141
        float wx = 0f, wy = 0f;
        if (wishspeed > 0f) {
            wx = wvx / wishspeed;
            wy = wvy / wishspeed;
        }

        // tfgm:2146-2150 — after the normalise, as written.
        if (wishspeed != 0f && wishspeed > mv.maxSpeed) {
            wishspeed = mv.maxSpeed;
        }
        // fx/fy are the renormalised planar forward; fwdX/fwdY still carry cos(pitch), which the
        // back-speed clamp cannot use.
        return new float[]{wx, wy, wishspeed, fx, fy};
    }

    private void walkMove(MoveData mv) {
        float[] w = buildWish(mv);
        float wx = w[0], wy = w[1], wishspeed = w[2];
        // No extra clientMaxSpeed clamp here: checkParameters wrote the MIN into mv.maxSpeed (gm:999)
        // and buildWish already clamped wishspeed to it (tfgm:2146-2150).

        mv.velZ = 0f;
        accelerate(mv, wx, wy, 0f, wishspeed, ACCELERATE);
        mv.velZ = 0f;

        // tfgm:1808-1815 — x/y rescaled to m_flMaxSpeed on every ground substep, before the destination
        // is built. Nothing else bounds total ground speed: checkVelocity is per-axis at 3500 and
        // preventBunnyJumping only fires on a jump.
        float flNewSpeed = mv.speed();                          // tfgm:1809 (z is 0 here)
        if (flNewSpeed > mv.maxSpeed) {                         // tfgm:1810
            float flScale = mv.maxSpeed / flNewSpeed;           // tfgm:1812
            mv.velX *= flScale;                                 // tfgm:1813
            mv.velY *= flScale;                                 // tfgm:1814
        }

        clampBackSpeed(mv, w[3], w[4]);                         // tfgm:1832-1861

        // StayOnGround is not called here: TF folded it into categorizePosition and commented out both
        // WalkMove call sites (tfgm:1909-1910, :1942-1943).

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

    /**
     * {@code tf_clamp_back_speed}, tfgm:1832-1861. ConVars at tfgm:47-48 (0.9 and 100). Backpedalling
     * caps at {@code 0.9 * 240 = 216} hu/s and only bites within acos(216/240) = 25.8° of straight back,
     * so S+A and S+D are already correct at 240.
     *
     * @param fx,fy the renormalised planar forward, not {@code fwdX/fwdY} (which still carry cos(pitch))
     */
    private void clampBackSpeed(MoveData mv, float fx, float fy) {
        if (mv.speed2D() <= TF_CLAMP_BACK_SPEED_MIN) return;         // tfgm:1836

        float flDot = mv.velX * fx + mv.velY * fy;                   // tfgm:1840
        if (flDot >= 0f) return;                                     // only when moving backwards

        float flMaxBackSpeed = mv.maxSpeed * TF_CLAMP_BACK_SPEED;    // tfgm:1843 -> 216
        if (-flDot <= flMaxBackSpeed) return;

        // Decompose, clamp the backward component, reassemble — an exact identity because both basis
        // vectors are planar and unit (tfgm:1756-1759).
        float backX = fx * flDot, backY = fy * flDot;                // tfgm:1846
        float rightPartX = mv.velX - backX, rightPartY = mv.velY - backY;

        float scale = flMaxBackSpeed / -flDot;                       // tfgm:1851
        backX *= scale;
        backY *= scale;

        mv.velX = backX + rightPartX;                                // tfgm:1853
        mv.velY = backY + rightPartY;

        float sp = mv.speed2D();                                     // tfgm:1855-1861 — re-run
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

    // ---- CTFGameMovement::CheckJumpButton, tfgm:1160 ----

    private boolean checkJumpButton(MoveData mv) {
        // canJump(): treated as true for a plain Soldier — no stun, taunt or kart states in scope.

        // tfgm:1221-1227 refuses unconditionally for any non-Scout with FL_DUCKING
        // (bAllow = bScout && !bOnGround, false for a Soldier). It sits before the pogo gate because
        // :1221 returns before the oldButtons latch at :1244.
        if (ducked) return false;                                   // tfgm:1221-1227
        if (ducking && ducked) return false;                        // tfgm:1231
        if ((mv.oldButtons & Buttons.IN_JUMP) != 0) return false;   // tfgm:1235 — the pogo gate

        if (!onGround) {                                            // tfgm:1244-1246
            mv.oldButtons |= Buttons.IN_JUMP;                       // no air dash for a Soldier
            return false;
        }

        preventBunnyJumping(mv);                                    // tfgm:1258
        onGround = false;                                           // tfgm:1271

        float groundFactor = 1.0f;   // tfgm:1279-1282, surfaceproperties gives 1.0 on `default`
        float jumpMod = 1.0f;        // tfgm:1288-1299, 1.0 stock (no mod_jump_height item)
        float flMul = (JUMP_IMPULSE * jumpMod) * groundFactor;      // tfgm:1315

        if (ducking || ducked) {
            mv.velZ = flMul;                                        // tfgm:1330  ASSIGN
        } else {
            mv.velZ += flMul;                                       // tfgm:1334  ADD
        }

        // Runs here and again at tfgm:2692, so three half-steps of gravity land on a jump substep and a
        // stock jump launches at 271 hu/s rather than 277.
        finishGravity(mv);                                          // tfgm:1338

        mv.oldButtons |= Buttons.IN_JUMP;                           // tfgm:1345
        return true;
    }

    /**
     * {@code PreventBunnyJumping}, tfgm:1092. Runs on every successful ground jump, scales the full 3D
     * vector including z (:1110), and caps against {@code player->m_flMaxspeed} (240 → 288), not
     * {@code mv->m_flMaxSpeed} (520).
     */
    private void preventBunnyJumping(MoveData mv) {
        float maxScaled = BUNNYJUMP_MAX_SPEED_FACTOR * mv.clientMaxSpeed;   // tfgm:1098
        if (maxScaled <= 0f) return;

        float spd = mv.speed();                                             // tfgm:1102 — 3D
        if (spd <= maxScaled) return;                                       // tfgm:1103

        float f = maxScaled / spd;                                          // tfgm:1107
        mv.velX *= f;
        mv.velY *= f;
        mv.velZ *= f;                                                       // tfgm:1110 — z too
    }

    // ---- CTFGameMovement::CategorizePosition, tfgm:2334 ----

    /**
     * Ground detection and the {@code m_surfaceFriction} regime: reset to 1.0 every call (tfgm:2341),
     * early return at 1.0 while {@code vz > 250} (test :2358, return :2368), 0.25 only when airborne and
     * rising (:2430-2433), 1.0 descending, and 1.0 on ground via {@code CategorizeGroundSurface}. The
     * {@code vz} tested is mid-substep, after {@code startGravity} and before {@code finishGravity}.
     */
    private void categorizePosition(MoveData mv) {
        surfaceFriction = DEFAULT_SURFACE_FRICTION;                 // tfgm:2341

        if (mv.velZ > GROUND_DETACH_SPEED) {                        // tfgm:2358
            onGround = false;                                       // tfgm:2367
            return;                                                 // tfgm:2368 — stays at 1.0
        }

        // tfgm:2378-2383 — when already grounded, TF2 extends the down-probe by the step size ("so we
        // don't bounce down slopes") and sets bMoveToEndPos, which also gates the folded StayOnGround
        // snap at :2440. STEP_SIZE_PORT (0.6 b) rather than sv_stepsize 18 hu, which is 0.395 b at this
        // scale — below a half-slab rise, so a flat probe detaches the player on alternating slabs.
        boolean moveToEndPos = onGround;                            // tfgm:2378
        float probe = 2.0f + (moveToEndPos ? STEP_SIZE_PORT : 0f);  // tfgm:2382
        world.trace(trace, hullHeight(), mv.originX, mv.originY, mv.originZ,
                    mv.originX, mv.originY, mv.originZ - probe);

        boolean standable = trace.fraction < 1.0f && trace.normalZ >= WALKABLE_NORMAL_Z;

        if (!standable) {                                           // tfgm:2426 — normal.z < 0.7
            onGround = false;
            if (mv.velZ > 0f) {                                     // tfgm:2430-2431 — RISING ONLY
                surfaceFriction = AIRBORNE_RISING_SURFACE_FRICTION; // tfgm:2433
            }
            return;
        }

        boolean wasAirborne = !onGround;
        onGround = true;
        surfaceFriction = DEFAULT_SURFACE_FRICTION;                 // CategorizeGroundSurface -> 1.0

        if (wasAirborne) {
            mv.velZ = 0f;                                           // gm:3649 — zeroing 1 of 3
        }

        // StayOnGround, folded in at tfgm:2437-2455 — gated on bMoveToEndPos, so it only snaps when the
        // player was already grounded, never on the landing substep.
        if (moveToEndPos && !trace.startSolid && trace.fraction > 0f && trace.fraction < 1.0f) {
            mv.originZ = trace.endZ;                                // tfgm:2440-2443
        }
    }

    private void checkFalling() {
        if (onGround) {
            fallVelocity = 0f;
        }
    }

    // ---- CheckVelocity, gm:3048. Per axis, not by magnitude. ----

    private void checkVelocity(MoveData mv) {
        mv.velX = clampAxis(mv.velX);
        mv.velY = clampAxis(mv.velY);
        mv.velZ = clampAxis(mv.velZ);
    }

    private static float clampAxis(float v) {
        if (Float.isNaN(v)) return 0f;
        if (v > MAX_VELOCITY) return MAX_VELOCITY;                  // gm:3075-3083
        if (v < -MAX_VELOCITY) return -MAX_VELOCITY;
        return v;
    }

    // ---- TryPlayerMove, gm:2558. The 4-bump loop. ----

    /**
     * Sweep, clip, repeat — up to four bumps. On a clipped bump the origin adopts the ray prefix
     * {@code origin += want * fraction}, never a query's fully-resolved endpoint; the stuck probe moves
     * nothing; and {@code numplanes} resets to zero on any bump that makes progress (:2663), so planes
     * accumulate only across consecutive zero-progress bumps.
     *
     * @return Source's {@code blocked} bitfield: 1 = floor, 2 = wall/step, 4 = allsolid
     */
    private int tryPlayerMove(MoveData mv) {
        int blocked = 0;
        int numplanes = 0;
        float[] planes = new float[MAX_CLIP_PLANES * 3];

        float timeLeft = DT;                                        // gm:2582

        float primalX = mv.velX, primalY = mv.velY, primalZ = mv.velZ;
        float origX = mv.velX, origY = mv.velY, origZ = mv.velZ;

        for (int bump = 0; bump < NUM_BUMPS; bump++) {              // gm:2573
            if (mv.velX == 0f && mv.velY == 0f && mv.velZ == 0f) break;

            float endX = mv.originX + mv.velX * timeLeft;
            float endY = mv.originY + mv.velY * timeLeft;
            float endZ = mv.originZ + mv.velZ * timeLeft;

            world.trace(trace, hullHeight(), mv.originX, mv.originY, mv.originZ, endX, endY, endZ);

            if (trace.allSolid) {                                   // gm:2628
                mv.velX = mv.velY = mv.velZ = 0f;
                return 4;
            }

            // Anti-stuck probe, gm:2636-2649: fires on a clear sweep (fraction == 1), re-tests unswept
            // at the endpoint, and breaks at :2648 before the origin is adopted at :2661. Partial
            // transcription — Valve also fires on `stuck.fraction != 1.0f`, which an unswept Minecraft
            // query cannot express.
            if (trace.fraction == 1.0f && world.solidAt(hullHeight(), endX, endY, endZ)) {
                mv.velX = mv.velY = mv.velZ = 0f;               // gm:2647
                break;                                          // gm:2648
            }

            if (trace.fraction > 0f) {
                // Adopt only the swept prefix.
                mv.originX += (endX - mv.originX) * trace.fraction;
                mv.originY += (endY - mv.originY) * trace.fraction;
                mv.originZ += (endZ - mv.originZ) * trace.fraction;
                origX = mv.velX;
                origY = mv.velY;
                origZ = mv.velZ;
                numplanes = 0;                                      // gm:2663 — reset on progress
            }

            if (trace.fraction == 1.0f) break;                      // moved the whole way

            if (trace.normalZ > WALKABLE_NORMAL_Z) blocked |= 1;    // gm:2680-2682 floor
            if (trace.normalZ == 0f) blocked |= 2;                  // gm:2686-2688 wall/step
            // A ceiling (normalZ == -1) sets neither bit.

            timeLeft -= timeLeft * trace.fraction;

            if (numplanes >= MAX_CLIP_PLANES) {                     // gm:2696-2702 — unreachable
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
                // Two-plane crease, gm:2736-2760. An interior 90-degree corner reaches it in Minecraft.
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
                    if (numplanes != 2) {                           // gm:2753 — all-planes fallback
                        mv.velX = mv.velY = mv.velZ = 0f;
                        break;
                    }
                    // Slide along the crease.
                    float dirX = planes[1] * planes[5] - planes[2] * planes[4];
                    float dirY = planes[2] * planes[3] - planes[0] * planes[5];
                    float dirZ = planes[0] * planes[4] - planes[1] * planes[3];

                    float lenSq = dirX * dirX + dirY * dirY + dirZ * dirZ;
                    if (lenSq < Unverified.CREASE_DEGENERATE_EPSILON) {
                        // Not in Source: two exactly opposite normals are authorable in Minecraft
                        // (a one-block slot) and divide by zero in the C++.
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

                // If the new velocity opposes the original, stop dead — prevents corner tunnelling.
                if (mv.velX * primalX + mv.velY * primalY + mv.velZ * primalZ <= 0f) {
                    mv.velX = mv.velY = mv.velZ = 0f;
                    break;
                }
            }
        }
        return blocked;
    }

    /**
     * {@code ClipVelocity}, gm:3144. {@code overbounce} is
     * {@code 1.0 + sv_bounce * (1 - surfaceFriction)} at the call site (gm:2731) and {@code sv_bounce}
     * is 0, so it collapses to exactly 1.0 for floor and wall alike.
     */
    private float[] clipVelocity(float vx, float vy, float vz,
                                 float nx, float ny, float nz, float overbounce) {
        float backoff = (vx * nx + vy * ny + vz * nz) * overbounce;

        float outX = vx - nx * backoff;
        float outY = vy - ny * backoff;
        float outZ = vz - nz * backoff;

        // gm:3178-3183 — iterate the adjustment back onto the plane.
        float adjust = outX * nx + outY * ny + outZ * nz;
        if (adjust < 0.0f) {
            outX -= nx * adjust;
            outY -= ny * adjust;
            outZ -= nz * adjust;
        }
        return new float[]{outX, outY, outZ};
    }

    // ---- CTFGameMovement::StepMove, tfgm:2829. High road first. ----

    /**
     * TF's {@code StepMove} is inverted relative to the base engine's: it tries the high road first
     * (tfgm:2845-2875) and falls back to the low road only on failure. Every assignment that sets
     * {@code bLowRoad} also clears {@code bUpRoad}, making the base algorithm's distance-comparison and
     * z-splicing block dead code.
     */
    private void stepMove(MoveData mv, float destX, float destY, float destZ) {
        float startX = mv.originX, startY = mv.originY, startZ = mv.originZ;
        float velX = mv.velX, velY = mv.velY, velZ = mv.velZ;

        // --- high road: step up, move, settle down ---
        // DIST_EPSILON on both step traces (tfgm:2849, :2861); symmetric, so it cancels on flat ground.
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

        // Valve adopts first (tfgm:2864-2866) and tests after, so the equality term below sees the
        // post-down-trace position.
        if (!trace.startSolid && !trace.allSolid) {
            mv.originZ = trace.endZ;
        }

        boolean lowRoad = (trace.fraction != 1.0f && trace.normalZ < WALKABLE_NORMAL_Z)
                       || (mv.originX == startX && mv.originY == startY && mv.originZ == startZ);
        if (!lowRoad) return;                                       // tfgm:2870-2875

        // --- low road: the plain slide, only reached when the high road failed ---
        mv.originX = startX;
        mv.originY = startY;
        mv.originZ = startZ;
        mv.velX = velX;
        mv.velY = velY;
        mv.velZ = velZ;
        tryPlayerMove(mv);
    }

    // ---- CTFGameMovement::Duck, tfgm:3389 ----

    /**
     * The live duck path. {@code HandleDuck}/{@code HandleUnDuck}/{@code TestDuck} (tfgm:3012-3173) sit
     * inside {@code #if 0} and are not compiled. Air-ducking is instantaneous: the {@code || bInAir}
     * term (tfgm:3267, :3339) fires finishDuck/finishUnDuck on the first airborne substep regardless of
     * the timer, and the origin shift finishDuck applies while airborne is the crouch-jump lift.
     */
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
            // The release re-arms the timer. Without it a sub-200 ms crouch tap leaves `ducking` set
            // here with no path to clear it, and checkJumpButton then takes the ASSIGN branch forever.
            if (ducked) {                                           // tfgm:3311-3314 — tested first
                duckTime = TIME_TO_UNDUCK * 1000.0f;
            } else if (ducking) {                                   // tfgm:3315-3325 — invert time
                float duckMs = TIME_TO_DUCK * 1000.0f;
                duckTime = ((duckMs - duckTime) / duckMs) * (TIME_TO_UNDUCK * 1000.0f);
            }
            ducking = true;
        }

        if (wantsDuck) {
            if (!ducked && (inAir || duckTime <= 0f)) {              // tfgm:3267 — || bInAir
                finishDuck(mv, inAir);
            }
        } else if (ducked || ducking) {                             // tfgm:3332-3333
            // Source tests CanUnduck at :3330 before the timer at :3339; the other order skips the
            // latch below and leaves ducking && ducked true under a one-block ceiling.
            if (canUnduck(mv)) {
                if (inAir || duckTime <= 0f) {                       // tfgm:3339
                    finishUnDuck(mv, inAir);
                }
            } else {                                                // tfgm:3352-3363 — the latch
                duckTime = TIME_TO_UNDUCK * 1000.0f;
                ducked = true;
                ducking = false;
            }
        }

        // tfgm:3398-3405 — Duck() latches IN_DUCK into oldButtons mid-substep and FinishMove overwrites
        // oldButtons wholesale afterwards; both writes feed the next substep's duck edge detection.
        if (wantsDuck) {
            mv.oldButtons |= Buttons.IN_DUCK;
        } else {
            mv.oldButtons &= ~Buttons.IN_DUCK;
        }
    }

    /**
     * {@code CTFGameMovement::HandleDuckingSpeedCrop}, tfgm:3371. Ground-only (gm:4308 requires
     * {@code FL_DUCKING && GetGroundEntity() != NULL}), latched once via {@code m_iSpeedCropped}, and
     * run inside {@code Duck()} so the crop is baked into forward/side before wishdir is built. TF's
     * override also zeroes movement when {@code IsLoser()}, which is always false in scope.
     */
    private void handleDuckingSpeedCrop(MoveData mv) {
        if ((speedCropped & SPEED_CROPPED_DUCK) != 0) return;
        if (!(ducked && onGround)) return;                          // gm:4308

        mv.forwardMove *= DUCK_SPEED_CROP;                          // gm:4310
        mv.sideMove *= DUCK_SPEED_CROP;
        mv.upMove *= DUCK_SPEED_CROP;
        speedCropped |= SPEED_CROPPED_DUCK;                         // gm:4314
    }

    /**
     * {@code FinishDuck}, gm:4215. While airborne this adds the hull difference to the origin
     * (gm:4235-4245), and that shift is the crouch-jump lift.
     */
    private void finishDuck(MoveData mv, boolean inAir) {
        ducked = true;
        ducking = false;
        if (inAir) {
            mv.originZ += DUCK_AIRBORNE_ORIGIN_SHIFT;
        }
    }

    /** {@code FinishUnDuck}, gm:4113. The mirror image. */
    private void finishUnDuck(MoveData mv, boolean inAir) {
        ducked = false;
        ducking = false;
        duckTime = 0f;                                              // gm:4144
        if (inAir) {
            mv.originZ -= DUCK_AIRBORNE_ORIGIN_SHIFT;
        }
    }

    /** {@code CanUnduck}, gm:4069 — is there room for the standing hull? */
    private boolean canUnduck(MoveData mv) {
        return !world.solidAt(HULL_HEIGHT_STANDING, mv.originX, mv.originY, mv.originZ);
    }

    private float hullHeight() {
        return ducked ? HULL_HEIGHT_DUCKED : HULL_HEIGHT_STANDING;
    }

    // ---- accessors ----

    public boolean onGround() {
        return onGround;
    }

    public boolean ducked() {
        return ducked;
    }

    public float surfaceFriction() {
        return surfaceFriction;
    }

    public float eyeHeight() {
        return ducked ? VIEW_HEIGHT_DUCKED : VIEW_HEIGHT_STANDING;
    }

    /** Seed the simulation from a known Minecraft state (spawn, teleport, dimension change). */
    public void reset(boolean grounded) {
        onGround = grounded;
        surfaceFriction = DEFAULT_SURFACE_FRICTION;
        ducked = false;
        ducking = false;
        duckTime = 0f;
        fallVelocity = 0f;
        speedCropped = 0;
    }

    /**
     * {@code CBaseEntity::ApplyAbsVelocityImpulse}, baseentity_shared.cpp:2426-2459 — the self-blast
     * entry point. Velocity only: the ground flag belongs to {@code CategorizePosition}, which drops it
     * only above {@link SourceFeel#GROUND_DETACH_SPEED} (gamemovement.cpp:4612 /
     * tf_gamemovement.cpp:2358), so an impulse leaving {@code velZ <= 250} while grounded has its
     * vertical component deleted next substep by {@code fullWalkMove}'s {@code if (onGround) velZ = 0}.
     */
    public void applyAbsVelocityImpulse(MoveData mv, float x, float y, float z) {
        if (x == 0f && y == 0f && z == 0f) return;                      // bes:2428

        // CheckEntityVelocity, bes:1103-1126. The cheap test is per-axis, the rescale that follows is on
        // the whole vector, and an absurd impulse is discarded rather than clamped.
        final float r = SourceFeel.MAX_ENTITY_SPEED;
        if (!(x > -r && x < r && y > -r && y < r && z > -r && z < r)) {
            float speed = (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
            if (speed >= r * 100f) return;                              // bes:1121 discard
            float s = r / speed;                                        // bes:1117 rescale
            x *= s;
            y *= s;
            z *= s;
        }

        mv.velX += x;
        mv.velY += y;
        mv.velZ += z;
    }

    /**
     * {@code CTFPlayer::ApplyGenericPushbackImpulse}, tf_player.cpp:3371-3401 — airblast, knockback and
     * rage, not the self-blast path. This one does clear the ground flag, and floors the vertical
     * component at {@link SourceFeel#JUMP_MIN_SPEED} first when grounded.
     */
    public void applyGenericPushbackImpulse(MoveData mv, float x, float y, float z) {
        if (onGround && z < SourceFeel.JUMP_MIN_SPEED) z = SourceFeel.JUMP_MIN_SPEED;  // :3390-3393
        onGround = false;                                                              // :3398
        applyAbsVelocityImpulse(mv, x, y, z);                                          // :3401
    }

    /** @deprecated the name says nothing about the ground flag; call the cited method instead. */
    @Deprecated
    public void addImpulse(MoveData mv, float x, float y, float z) {
        applyAbsVelocityImpulse(mv, x, y, z);
    }
}
