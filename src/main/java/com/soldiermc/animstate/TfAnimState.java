package com.soldiermc.animstate;

/** Locomotion state machine and pose parameters from TF2's player anim state. */
public final class TfAnimState {

    private static final float MOVING_MINIMUM_SPEED = 0.5f;
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

    private Activity prevActivity = Activity.STAND_IDLE;
    private float prevCycle;
    private float blendWeight = 1.0f;

    private static final float TRANSITION_SECONDS = 0.2f;

    private boolean jumping;
    private boolean firstJumpFrame;
    private float jumpStart;
    private boolean inAirWalk;

    private float poseMoveX;
    private float poseMoveY;
    private float playbackRate = 1.0f;

    private float currentFeetYaw;
    private float goalFeetYaw;
    private boolean feetInitialised;

    private float poseBodyPitch;
    private float poseBodyYaw;

    private boolean landActive;
    private float landCycle;

    private static final float JUMPLAND_SECONDS = 1.0f;

    private String slot0Gesture;
    private float slot0Cycle;
    private float slot0Duration;

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

    public void tickGestures(float dt) {
        if (slot0Gesture != null) {
            slot0Cycle += dt / Math.max(slot0Duration, 1e-4f);
            if (slot0Cycle > 1f) {
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

    public void onJump(float now) {
        jumping = true;
        firstJumpFrame = true;
        jumpStart = now;
        cycle = 0f;
    }

    public void update(float speed2D, float velZ, boolean onGround, boolean ducked,
                       float eyeYaw, float eyePitch, float moveYaw, float now, float dt,
                       float runBoxEdgeSpeed, float crouchBoxEdgeSpeed) {

        // The transitioner fades out last frame's cycle, so capture it before any reset.
        final float cycleAtEntry = cycle;

        Activity ideal = Activity.STAND_IDLE;
        boolean handled = false;

        // Like Source, the airwalk test does not check onGround.
        if (!ducked && (velZ > AIRWALK_MIN_VELZ || inAirWalk)) {
            if (onGround && inAirWalk) {
                inAirWalk = false;
                cycle = 0f;
                startLandGesture();
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
                startLandGesture();
            }
            if (jumping) {
                ideal = (now - jumpStart > 0.5f) ? Activity.JUMP_FLOAT : Activity.JUMP_START;
            }
        }
        handled = jumping || inAirWalk;

        if (!handled && ducked) {
            ideal = speed2D > MOVING_MINIMUM_SPEED ? Activity.CROUCH_WALK : Activity.CROUCH_IDLE;
            handled = true;
        }

        if (!handled) {
            ideal = speed2D > MOVING_MINIMUM_SPEED ? Activity.RUN : Activity.STAND_IDLE;
        }

        // The cycle carries over: Source's client ResetSequence never writes it.
        if (ideal != activity) {
            prevActivity = activity;
            prevCycle = cycleAtEntry;
            blendWeight = 0f;
            activity = ideal;
        }

        if (blendWeight < 1f) {
            blendWeight = Math.min(1f, blendWeight + dt / TRANSITION_SECONDS);
            prevCycle += dt / Math.max(durationFor(prevActivity), 1e-4f);
            prevCycle -= (float) Math.floor(prevCycle);
        }

        tickGestures(dt);

        // Valve uses the full 3D speed here, not the horizontal one.
        updateFeetYaw((float) Math.sqrt(speed2D * speed2D + velZ * velZ), eyeYaw, dt);

        // Negated as in ComputePoseParam_Aim*; the aim matrix's pose keys descend.
        poseBodyPitch = -eyePitch;
        poseBodyYaw = -normalize180(eyeYaw - currentFeetYaw);

        computePose(speed2D, eyeYaw, moveYaw,
                activity == Activity.CROUCH_WALK ? crouchBoxEdgeSpeed : runBoxEdgeSpeed);

        // Always 1: TF2 is LEGANIM_9WAY, which skips ComputePlaybackRate.
        playbackRate = 1.0f;

        cycle += dt * playbackRate / Math.max(durationHintSeconds(), 1e-4f);
        cycle -= (float) Math.floor(cycle);
    }

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
            // Valve steps the goal by 45 degrees rather than clamping it near eyeYaw.
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

    /** As in ConvergeYawAngles, only the delta's sign comes from the wrapped value. */
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

    /** Move direction on the square, not the circle, so diagonals reach the corner clips. */
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

    public float blendWeight() {
        return blendWeight;
    }

    public Activity activity() {
        return activity;
    }

    public float cycle() {
        return cycle;
    }

    public float poseMoveX() {
        return poseMoveX;
    }

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
