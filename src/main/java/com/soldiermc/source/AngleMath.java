package com.soldiermc.source;

/** mathlib_base.cpp. Source axes: +X forward, +Y left, +Z up; positive pitch is down. */
public final class AngleMath {

    private AngleMath() {
    }

    public static void angleVectors(float pitch, float yaw, float roll, float[] out) {
        double sy = Math.sin(Math.toRadians(yaw));
        double cy = Math.cos(Math.toRadians(yaw));
        double sp = Math.sin(Math.toRadians(pitch));
        double cp = Math.cos(Math.toRadians(pitch));
        double sr = Math.sin(Math.toRadians(roll));
        double cr = Math.cos(Math.toRadians(roll));

        out[0] = (float) (cp * cy);
        out[1] = (float) (cp * sy);
        out[2] = (float) (-sp);

        out[3] = (float) (-1 * sr * sp * cy + -1 * cr * -sy);
        out[4] = (float) (-1 * sr * sp * sy + -1 * cr * cy);
        out[5] = (float) (-1 * sr * cp);

        out[6] = (float) (cr * sp * cy + -sr * -sy);
        out[7] = (float) (cr * sp * sy + -sr * cy);
        out[8] = (float) (cr * cp);
    }

    public static void forward(float pitch, float yaw, float[] out) {
        double sy = Math.sin(Math.toRadians(yaw));
        double cy = Math.cos(Math.toRadians(yaw));
        double sp = Math.sin(Math.toRadians(pitch));
        double cp = Math.cos(Math.toRadians(pitch));
        out[0] = (float) (cp * cy);
        out[1] = (float) (cp * sy);
        out[2] = (float) (-sp);
    }

    /** Returns pitch and yaw in [0, 360), so a pitch of 350 is 10 degrees up. */
    public static void vectorAngles(float x, float y, float z, float[] out) {
        float yaw;
        float pitch;
        if (y == 0f && x == 0f) {
            yaw = 0f;
            pitch = z > 0f ? 270f : 90f;
        } else {
            yaw = (float) (Math.atan2(y, x) * 180.0 / Math.PI);
            if (yaw < 0f) yaw += 360f;

            double tmp = Math.sqrt((double) x * x + (double) y * y);
            pitch = (float) (Math.atan2(-z, tmp) * 180.0 / Math.PI);
            if (pitch < 0f) pitch += 360f;
        }
        out[0] = pitch;
        out[1] = yaw;
    }

    public static float normalize(float[] v, int off) {
        double len = Math.sqrt((double) v[off] * v[off]
                + (double) v[off + 1] * v[off + 1]
                + (double) v[off + 2] * v[off + 2]);
        if (len > 1e-9) {
            float inv = (float) (1.0 / len);
            v[off] *= inv;
            v[off + 1] *= inv;
            v[off + 2] *= inv;
        }
        return (float) len;
    }
}
