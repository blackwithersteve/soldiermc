package com.soldiermc.bridge;

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

    public static final class Sample {
        public float pitch;
        public float yaw;
        public float forwardMove;
        public float sideMove;
        public int buttons;
    }
}
