package com.soldiermc.bridge;

/**
 * A timestamped ring of per-rendered-frame input samples, client-side and unsynchronised. Air-strafe
 * gain follows the fresh input sample rate R, not the substep rate: sampling once per Minecraft tick
 * pins R at 20 and gives 303 hu/s on a rocket jump against TF2's 413.
 */
public final class InputRing {

    private static final int CAPACITY = 256;

    private final long[] timeNanos = new long[CAPACITY];
    private final float[] pitch = new float[CAPACITY];
    private final float[] yaw = new float[CAPACITY];
    private final int[] buttons = new int[CAPACITY];
    private final float[] forward = new float[CAPACITY];
    private final float[] side = new float[CAPACITY];

    private int head = -1;
    private int count;

    /** Record one frame's input. Called from the per-frame mixin. */
    public void sample(long nanos, float viewPitch, float viewYaw,
                       float forwardMove, float sideMove, int buttonBits) {
        head = (head + 1) % CAPACITY;
        timeNanos[head] = nanos;
        pitch[head] = viewPitch;
        yaw[head] = viewYaw;
        forward[head] = forwardMove;
        side[head] = sideMove;
        buttons[head] = buttonBits;
        if (count < CAPACITY) count++;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    /**
     * The freshest sample at or before {@code nanos}, written into {@code out}. If the ring holds
     * nothing that old, the oldest available sample is used.
     *
     * @return false if the ring is empty entirely
     */
    public boolean sampleAt(long nanos, Sample out) {
        if (count == 0) return false;

        int best = head;
        for (int i = 0; i < count; i++) {
            int idx = Math.floorMod(head - i, CAPACITY);
            if (timeNanos[idx] <= nanos) {
                best = idx;
                break;
            }
            best = idx; // keep walking back; ends on the oldest if none qualify
        }

        out.pitch = pitch[best];
        out.yaw = yaw[best];
        out.forwardMove = forward[best];
        out.sideMove = side[best];
        out.buttons = buttons[best];
        return true;
    }

    /**
     * The sample {@code back} frames before the newest, written into {@code out} — newest for the
     * last substep, walking back for earlier ones. Unlike timestamp matching this pins input latency
     * at one frame; when the ring is short the oldest is reused and R collapses toward the frame
     * rate, which {@link #freshRate} makes visible.
     *
     * @return false if the ring is empty
     */
    public boolean sampleFromEnd(int back, Sample out) {
        if (count == 0) return false;
        int clamped = Math.min(back, count - 1);
        int idx = Math.floorMod(head - clamped, CAPACITY);

        out.pitch = pitch[idx];
        out.yaw = yaw[idx];
        out.forwardMove = forward[idx];
        out.sideMove = side[idx];
        out.buttons = buttons[idx];
        return true;
    }

    /** How many distinct frames were recorded in the last {@code windowNanos} — the live R. */
    public int freshRate(long nowNanos, long windowNanos) {
        int n = 0;
        for (int i = 0; i < count; i++) {
            int idx = Math.floorMod(head - i, CAPACITY);
            if (nowNanos - timeNanos[idx] > windowNanos) break;
            n++;
        }
        return n;
    }

    public void clear() {
        head = -1;
        count = 0;
    }

    /** One reconstructed usercmd. */
    public static final class Sample {
        public float pitch;
        public float yaw;
        public float forwardMove;
        public float sideMove;
        public int buttons;
    }
}
