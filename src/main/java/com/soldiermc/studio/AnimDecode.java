package com.soldiermc.studio;

/** Source animation value decoding (bone_setup.cpp) and quaternion helpers. */
public final class AnimDecode {

    public static final int STUDIO_ANIM_RAWPOS = 0x01;
    public static final int STUDIO_ANIM_RAWROT = 0x02;
    public static final int STUDIO_ANIM_ANIMPOS = 0x04;
    public static final int STUDIO_ANIM_ANIMROT = 0x08;
    public static final int STUDIO_ANIM_DELTA = 0x10;
    public static final int STUDIO_ANIM_RAWROT2 = 0x20;

    public static final int STUDIO_LOOPING = 0x0001;
    public static final int STUDIO_DELTA = 0x0004;
    public static final int STUDIO_ALLZEROS = 0x0020;

    private AnimDecode() {
    }

    /** Valve float16: exponent 31 decodes to 65504, not infinity or NaN. */
    public static float float16(int u) {
        int s = (u >> 15) & 1;
        int e = (u >> 10) & 0x1F;
        int m = u & 0x3FF;
        double v;
        if (e == 0) {
            v = m / 1024.0 * Math.pow(2.0, -14);
        } else if (e == 31) {
            v = 65504.0;
        } else {
            v = (1.0 + m / 1024.0) * Math.pow(2.0, e - 15);
        }
        return (float) (s != 0 ? -v : v);
    }

    public static void vector48(Reader r, int o, float[] out) {
        out[0] = float16(r.u16At(o));
        out[1] = float16(r.u16At(o + 2));
        out[2] = float16(r.u16At(o + 4));
    }

    /** Quaternion48 bit layout: x:16, y:16, z:15, wneg:1. */
    public static void quat48(Reader r, int o, float[] out) {
        int xs = r.u16At(o);
        int ys = r.u16At(o + 2);
        int zs = r.u16At(o + 4);
        float x = (xs - 32768) * (1.0f / 32768.0f);
        float y = (ys - 32768) * (1.0f / 32768.0f);
        float z = ((zs & 0x7FFF) - 16384) * (1.0f / 16384.0f);
        boolean wneg = ((zs >> 15) & 1) != 0;
        float t = 1.0f - x * x - y * y - z * z;
        float w = t > 0 ? (float) Math.sqrt(t) : 0.0f;
        out[0] = x; out[1] = y; out[2] = z; out[3] = wneg ? -w : w;
    }

    /** Quaternion64, a little-endian uint64: x:21, y:21, z:21, wneg:1. */
    public static void quat64(Reader r, int o, float[] out) {
        long v = 0;
        for (int i = 7; i >= 0; i--) v = (v << 8) | r.u8At(o + i);
        long xs = v & 0x1FFFFFL;
        long ys = (v >>> 21) & 0x1FFFFFL;
        long zs = (v >>> 42) & 0x1FFFFFL;
        boolean wneg = ((v >>> 63) & 1) != 0;
        float x = (xs - 1048576) * (1.0f / 1048576.0f);
        float y = (ys - 1048576) * (1.0f / 1048576.0f);
        float z = (zs - 1048576) * (1.0f / 1048576.0f);
        float t = 1.0f - x * x - y * y - z * z;
        float w = t > 0 ? (float) Math.sqrt(t) : 0.0f;
        out[0] = x; out[1] = y; out[2] = z; out[3] = wneg ? -w : w;
    }

    /** RLE runs: {valid, total} byte header, valid shorts, then the last value repeats. */
    public static void extractAnimValue(Reader r, int base, int frame, float scale, float[] out) {
        if (base <= 0) {
            out[0] = 0f; out[1] = 0f;
            return;
        }
        int p = base;
        int valid = r.u8At(p);
        int total = r.u8At(p + 1);

        if (total == 1 && valid == 1) {
            float v = r.i16At(p + 2) * scale;
            out[0] = v; out[1] = v;
            return;
        }

        int k = frame;
        while (total <= k) {
            k -= total;
            p += (valid + 1) * 2;
            valid = r.u8At(p);
            total = r.u8At(p + 1);
            if (total == 0) {
                out[0] = 0f; out[1] = 0f;
                return;
            }
        }

        if (valid > k + 1) {
            out[0] = r.i16At(p + (k + 1) * 2) * scale;
            out[1] = r.i16At(p + (k + 2) * 2) * scale;
            return;
        }
        if (valid > k) {
            float v = r.i16At(p + (k + 1) * 2) * scale;
            out[0] = v; out[1] = v;
            return;
        }
        float v1 = r.i16At(p + valid * 2) * scale;
        if (total > k + 1) {
            out[0] = v1; out[1] = v1;
            return;
        }
        // The following frame is the next run's first value, past its header.
        out[0] = v1;
        out[1] = r.i16At(p + (valid + 2) * 2) * scale;
    }

    public static void angleQuaternion(float rx, float ry, float rz, float[] out) {
        double sy = Math.sin(rz * 0.5), cy = Math.cos(rz * 0.5);
        double sp = Math.sin(ry * 0.5), cp = Math.cos(ry * 0.5);
        double sr = Math.sin(rx * 0.5), cr = Math.cos(rx * 0.5);
        double srXcp = sr * cp, crXsp = cr * sp;
        double crXcp = cr * cp, srXsp = sr * sp;
        out[0] = (float) (srXcp * cy - crXsp * sy);
        out[1] = (float) (crXsp * cy + srXcp * sy);
        out[2] = (float) (crXcp * sy - srXsp * cy);
        out[3] = (float) (crXcp * cy + srXsp * sy);
    }

    public static void quatMult(float[] p, float[] q, float[] out) {
        float[] qq = q;
        if (p[0] * q[0] + p[1] * q[1] + p[2] * q[2] + p[3] * q[3] < 0f) {
            qq = new float[]{-q[0], -q[1], -q[2], -q[3]};
        }
        out[0] =  p[0] * qq[3] + p[1] * qq[2] - p[2] * qq[1] + p[3] * qq[0];
        out[1] = -p[0] * qq[2] + p[1] * qq[3] + p[2] * qq[0] + p[3] * qq[1];
        out[2] =  p[0] * qq[1] - p[1] * qq[0] + p[2] * qq[3] + p[3] * qq[2];
        out[3] = -p[0] * qq[0] - p[1] * qq[1] - p[2] * qq[2] + p[3] * qq[3];
    }

    public static void quatScale(float[] p, float t, float[] out) {
        float sinom = (float) Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
        sinom = Math.min(sinom, 1.0f);
        float sinsom = (float) Math.sin(Math.asin(sinom) * t);
        float k = sinsom / (sinom + 1.1920929e-7f);
        out[0] = p[0] * k;
        out[1] = p[1] * k;
        out[2] = p[2] * k;
        float r = 1.0f - sinsom * sinsom;
        if (r < 0f) r = 0f;
        r = (float) Math.sqrt(r);
        out[3] = p[3] < 0f ? -r : r;
    }

    public static void quatMA(float[] p, float s, float[] q, float[] out) {
        float[] scaled = new float[4];
        quatScale(q, s, scaled);
        float[] mult = new float[4];
        quatMult(p, scaled, mult);
        float n = (float) Math.sqrt(mult[0]*mult[0] + mult[1]*mult[1] + mult[2]*mult[2] + mult[3]*mult[3]);
        if (n == 0f) n = 1f;
        for (int i = 0; i < 4; i++) out[i] = mult[i] / n;
    }

    /** scale(p, s) * q, where quatMA is p * scale(q, s). */
    public static void quatSM(float s, float[] p, float[] q, float[] out) {
        float[] scaled = new float[4];
        quatScale(p, s, scaled);
        float[] mult = new float[4];
        quatMult(scaled, q, mult);
        float n = (float) Math.sqrt(mult[0] * mult[0] + mult[1] * mult[1]
                + mult[2] * mult[2] + mult[3] * mult[3]);
        if (n == 0f) n = 1f;
        for (int i = 0; i < 4; i++) out[i] = mult[i] / n;
    }

    public static void quatBlend(float[] q1, float[] q2, float s, float[] out) {
        float d = q1[0] * q2[0] + q1[1] * q2[1] + q1[2] * q2[2] + q1[3] * q2[3];
        float sign = d < 0 ? -1f : 1f;
        float n = 0f;
        for (int i = 0; i < 4; i++) {
            out[i] = q1[i] * (1 - s) + q2[i] * sign * s;
            n += out[i] * out[i];
        }
        n = (float) Math.sqrt(n);
        if (n == 0f) n = 1f;
        for (int i = 0; i < 4; i++) out[i] /= n;
    }
}
