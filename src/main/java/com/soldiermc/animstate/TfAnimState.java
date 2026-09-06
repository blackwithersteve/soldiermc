package com.soldiermc.animstate;

/**
 * The Soldier's locomotion state machine — {@code CalcMainActivity} and the pose-parameter
 * computation from TF2's player anim state. Pure Java, no Minecraft types, driven from the ported
 * movement engine's velocity, ground state and view angles.
 */
public final class TfAnimState {

    /** Below this the Soldier counts as standing still. */
    private static final float MOVING_MINIMUM_SPEED = 0.5f;

    /** The airwalk threshold. Above the Soldier's 289 jump impulse, so a plain jump never airwalks. */
    private static final float AIRWALK_MIN_VELZ = 300.0f;

    public enum Activity {
        STAND_IDLE,
        RUN,
        CROUCH_IDLE,
        CROUCH_WALK,
        JUMP_START,
        JUMP_FLOAT,
        AIRWALK,
    }

    private Activity activity = Activity.STAND_IDLE;
    private float cycle;

    /**
     * The outgoing sequence, kept playing while it fades out. {@code CSequenceTransitioner}: the old
     * sequence keeps advancing and its weight decays, so the skeleton eases from one to the other.
     */
    private Activity prevActivity = Activity.STAND_IDLE;
    private float prevCycle;
    private float blendWeight = 1.0f;   // 1 = fully on the current sequence

    /** Seconds to cross-fade. Source reads fadeintime per sequence; 0.2 is its common value. */
    private static final float TRANSITION_SECONDS = 0.2f;

    private boolean jumping;
    private boolean firstJumpFrame;
    private float jumpStart;
    private boolean inAirWalk;

    private float poseMoveX;
    private float poseMoveY;
    private float playbackRate = 1.0f;

    /**
     * Where the feet point, converged toward the goal rather than snapped to it. The feet turn at a
     * limited rate, the 9-way grid absorbs the difference, and the aim layer turns the upper body.
     */
    private float currentFeetYaw;
    private float goalFeetYaw;
    private boolean feetInitialised;

    /** Body pitch/yaw for the aim matrix, already negated per ComputePoseParam_Aim*. */
    private float poseBodyPitch;
    private float poseBodyYaw;

    /**
     * The landing overlay — {@code ACT_MP_JUMP_LAND} in {@code GESTURE_SLOT_JUMP}, a one-second
     * full-body additive over the top of the hard cut. No weight ramp: the clip's delta is identity
     * at both ends and frame 0 to 1 is a 61.7 degree snap that is the impact.
     */
    private boolean landActive;
    private float landCycle;

    /** {@code @jumpland_PRIMARY}: 31 frames at 30 fps, exactly one second. */
    private static final float JUMPLAND_SECONDS = 1.0f;

    /**
     * {@code GESTURE_SLOT_ATTACK_AND_RELOAD}, which also carries the voice-command gestures, so a
     * voice command interrupts a reload. Held as a name to keep this class on {@code java.*}; TF2
     * sets a gesture's weight to a hard 1.0 with no ramp.
     */
    private String slot0Gesture;
    private float slot0Cycle;
    private float slot0Duration;

    /** {@code RestartGesture}: the same gesture restarts in place, a different one replaces. */
    public void startGesture(String sequenceName, float durationSeconds) {
        slot0Gesture = sequenceName;
        slot0Cycle = 0f;
        slot0Duration = durationSeconds;
    }

    public String slot0Gesture() {
        return slot0Gesture;
    }

    public float slot0Cycle() {
        return slot0Cycle;
    }

    /**
     * Advance the gesture layers. Separate from {@link #update} so a gesture keeps running when the
     * locomotion machine is not being driven, instead of freezing mid-pose.
     */
    public void tickGestures(float dt) {
        if (slot0Gesture != null) {
            slot0Cycle += dt / Math.max(slot0Duration, 1e-4f);
            if (slot0Cycle > 1f) {          // ANIM_LAYER_AUTOKILL
                slot0Gesture = null;
                slot0Cycle = 0f;
            }
        }
        if (landActive) {
            landCycle += dt / JUMPLAND_SECONDS;
            if (landCycle > 1f) {
                landActive = false;
                landCycle = 0f;
            }
        }
    }

    private void startLandGesture() {
        landActive = true;
        landCycle = 0f;
    }

    public boolean landActive() {
        return landActive;
    }

    public float landCycle() {
        return landCycle;
    }

    /** Called where the movement engine applies the jump impulse. */
    public void onJump(float now) {
        jumping = true;
        firstJumpFrame = true;
        jumpStart = now;
        cycle = 0f;
    }

    /**
     * @param speed2D horizontal speed, hu/s
     * @param velZ    vertical velocity, hu/s
     * @param eyeYaw  view yaw in Source degrees
     * @param moveYaw the direction of travel in Source degrees
     * @param now     monotonic seconds
     */
    public void update(float speed2D, float velZ, boolean onGround, boolean ducked,
                       float eyeYaw, float eyePitch, float moveYaw, float now, float dt,
                       float runBoxEdgeSpeed, float crouchBoxEdgeSpeed) {

        // Source's transitioner stores the outgoing cycle from last frame's bone setup, so the
        // landing's SetCycle(0) never reaches it. Here `cycle` at entry is last frame's final value,
        // since it is advanced at the bottom of update(), so capture it before the resets below.
        final float cycleAtEntry = cycle;

        Activity ideal = Activity.STAND_IDLE;
        boolean handled = false;

        // ---- HandleJumping. The airwalk condition does not test !onGround.
        if (!ducked && (velZ > AIRWALK_MIN_VELZ || inAirWalk)) {
            if (onGround && inAirWalk) {
                inAirWalk = false;
                cycle = 0f;                      // the LANDING frame — ideal stays STAND_IDLE
                startLandGesture();              // RestartGesture(GESTURE_SLOT_JUMP, JUMP_LAND)
            } else if (!onGround) {
                ideal = Activity.AIRWALK;
                inAirWalk = true;
            }
        } else if (jumping) {
            if (firstJumpFrame) {
                firstJumpFrame = false;
                cycle = 0f;
            } else if (now - jumpStart > 0.2f && onGround) {
                jumping = false;
                cycle = 0f;
                // Both touchdown paths fire the gesture: an uncrouched rocket jump exceeds
                // velZ > 300 and lands through the airwalk exit above, a crouched one fails that
                // branch's !ducked gate, never latches airwalk, and lands here.
                startLandGesture();
            }
            if (jumping) {
                ideal = (now - jumpStart > 0.5f) ? Activity.JUMP_FLOAT : Activity.JUMP_START;
            }
        }
        handled = jumping || inAirWalk;

        // ---- HandleDucking
        if (!handled && ducked) {
            ideal = speed2D > MOVING_MINIMUM_SPEED ? Activity.CROUCH_WALK : Activity.CROUCH_IDLE;
            handled = true;
        }

        // ---- HandleMoving
        if (!handled) {
            ideal = speed2D > MOVING_MINIMUM_SPEED ? Activity.RUN : Activity.STAND_IDLE;
        }

        if (ideal != activity) {
            // Cross-fade from the pose currently on screen rather than cutting.
            prevActivity = activity;
            prevCycle = cycleAtEntry;
            blendWeight = 0f;
            activity = ideal;

            // The cycle is not reset here: ComputeMainSequence calls ResetSequence, whose client
            // overload (c_baseanimating.h:708) is SetSequence + ResetSequenceInfo and never writes
            // the cycle. Only RestartMainSequence does, from the jump, swim, death and anim-event
            // paths — the explicit resets above.
        }

        if (blendWeight < 1f) {
            blendWeight = Math.min(1f, blendWeight + dt / TRANSITION_SECONDS);
            // The outgoing sequence keeps advancing while it fades, rather than freezing.
            prevCycle += dt / Math.max(durationFor(prevActivity), 1e-4f);
            prevCycle -= (float) Math.floor(prevCycle);
        }

        tickGestures(dt);

        // Valve tests vecVelocity.Length(), the full 3D length, not the horizontal speed.
        updateFeetYaw((float) Math.sqrt(speed2D * speed2D + velZ * velZ), eyeYaw, dt);

        // ComputePoseParam_AimPitch is setPose(body_pitch, -eyePitch); _AimYaw is
        // setPose(body_yaw, -normalize180(eyeYaw - currentFeetYaw)). Both negations are cancelled by
        // the aim matrix's descending pose keys: yaw runs 45, 0, -45 across its three columns and
        // pitch 90, 45, 0, -45 down its four rows.
        poseBodyPitch = -eyePitch;
        poseBodyYaw = -normalize180(eyeYaw - currentFeetYaw);

        computePose(speed2D, eyeYaw, moveYaw,
                activity == Activity.CROUCH_WALK ? crouchBoxEdgeSpeed : runBoxEdgeSpeed);

        // Always 1: TF2 is LEGANIM_9WAY (multiplayer_animstate.cpp:104) and ComputePlaybackRate is
        // gated out for 9WAY (base_playeranimstate.cpp:545). Only the stride shortens with speed,
        // through the move_x/move_y scale in computePose.
        playbackRate = 1.0f;

        cycle += dt * playbackRate / Math.max(durationHintSeconds(), 1e-4f);
        cycle -= (float) Math.floor(cycle);
    }

    /**
     * Feet convergence. Moving, the goal is the view yaw; standing, the feet may lag it by up to 45°
     * before being dragged round.
     */
    private void updateFeetYaw(float speed3D, float eyeYaw, float dt) {
        if (!feetInitialised) {
            currentFeetYaw = eyeYaw;
            goalFeetYaw = eyeYaw;
            feetInitialised = true;
            return;
        }

        if (speed3D > 1.0f) {
            goalFeetYaw = eyeYaw;
        } else {
            // Valve steps the goal by a fixed 45° while the delta exceeds 45°, rather than clamping
            // it to eyeYaw ± 45: the goal walks down over successive frames (100° -> 55° -> 10°) so
            // the feet settle nearly on the view instead of pinning at a permanent 45° offset.
            float d = normalize180(goalFeetYaw - eyeYaw);
            if (Math.abs(d) > 45.0f) {
                goalFeetYaw += d > 0.0f ? -45.0f : 45.0f;
            }
        }

        goalFeetYaw = normalize180(goalFeetYaw);
        if (goalFeetYaw != currentFeetYaw) {
            convergeYawAngles(goalFeetYaw, dt);
        }
    }

    /**
     * {@code ConvergeYawAngles}: the fade scale and the snap test use the unnormalised magnitude,
     * and only the sign comes from the normalised delta. 720°/s is a hardcoded literal at the call
     * site, not a ConVar.
     */
    private void convergeYawAngles(float goal, float dt) {
        float delta = goal - currentFeetYaw;
        float deltaAbs = Math.abs(delta);
        delta = normalize180(delta);

        float scale = clamp(deltaAbs / 60.0f, 0.01f, 1.0f);
        float step = 720.0f * dt * scale;
        if (deltaAbs < step) {
            currentFeetYaw = goal;
        } else {
            currentFeetYaw += step * (delta < 0.0f ? -1.0f : 1.0f);
        }
        currentFeetYaw = normalize180(currentFeetYaw);
    }

    public float currentFeetYaw() {
        return currentFeetYaw;
    }

    public float poseBodyPitch() {
        return poseBodyPitch;
    }

    public float poseBodyYaw() {
        return poseBodyYaw;
    }

    /**
     * The 9-way pose values, projected onto the −1..1 box rather than the circle, so a diagonal run
     * reaches the corner clips.
     */
    private void computePose(float speed2D, float eyeYaw, float moveYaw, float boxEdgeSpeed) {
        if (speed2D <= MOVING_MINIMUM_SPEED) {
            poseMoveX = 0f;
            poseMoveY = 0f;
            return;
        }
        float flYaw = normalize180(-(normalize180(eyeYaw) - moveYaw));
        float r = (float) Math.toRadians(flYaw);
        float x = (float) Math.cos(r);
        float y = (float) -Math.sin(r);

        float inv = Math.max(Math.abs(x), Math.abs(y));
        if (inv != 0f) {
            x /= inv;
            y /= inv;
        }
        if (boxEdgeSpeed > speed2D && boxEdgeSpeed > 0f) {
            float k = speed2D / boxEdgeSpeed;
            x *= k;
            y *= k;
        }
        poseMoveX = x;
        poseMoveY = y;
    }

    /** Rough per-activity duration so the cycle advances at a sane rate before rates are read. */
    private float durationHintSeconds() {
        return durationFor(activity);
    }

    private static float durationFor(Activity a) {
        return switch (a) {
            case RUN -> 0.7f;
            case CROUCH_WALK -> 0.8667f;
            case AIRWALK -> 1.0f;
            case JUMP_START, JUMP_FLOAT -> 0.7667f;
            default -> 1.6667f;
        };
    }

    public Activity prevActivity() {
        return prevActivity;
    }

    public float prevCycle() {
        return prevCycle;
    }

    /** 1 = fully on the current sequence; below that a cross-fade is in progress. */
    public float blendWeight() {
        return blendWeight;
    }

    public Activity activity() {
        return activity;
    }

    public float cycle() {
        return cycle;
    }

    /** The value for {@code move_x} — the sequence's SECOND pose parameter index. */
    public float poseMoveX() {
        return poseMoveX;
    }

    /** The value for {@code move_y} — the sequence's FIRST pose parameter index. */
    public float poseMoveY() {
        return poseMoveY;
    }

    public float playbackRate() {
        return playbackRate;
    }

    public static float normalize180(float deg) {
        deg = deg % 360f;
        if (deg > 180f) deg -= 360f;
        if (deg < -180f) deg += 360f;
        return deg;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
