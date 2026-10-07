package com.soldiermc.studio;

/** Bone matrices as row-major matrix3x4_t: 12 floats, column 3 the translation. */
public final class Skeleton {

    private Skeleton() {
    }

    public static void quatToMatrix(float[] q, int qo, float[] p, int po, float[] out, int oo) {
        float x = q[qo], y = q[qo + 1], z = q[qo + 2], w = q[qo + 3];

        out[oo] = 1.0f - 2.0f * y * y - 2.0f * z * z;
        out[oo + 1] = 2.0f * x * y - 2.0f * w * z;
        out[oo + 2] = 2.0f * x * z + 2.0f * w * y;
        out[oo + 3] = p[po];

        out[oo + 4] = 2.0f * x * y + 2.0f * w * z;
        out[oo + 5] = 1.0f - 2.0f * x * x - 2.0f * z * z;
        out[oo + 6] = 2.0f * y * z - 2.0f * w * x;
        out[oo + 7] = p[po + 1];

        out[oo + 8] = 2.0f * x * z - 2.0f * w * y;
        out[oo + 9] = 2.0f * y * z + 2.0f * w * x;
        out[oo + 10] = 1.0f - 2.0f * x * x - 2.0f * y * y;
        out[oo + 11] = p[po + 2];
    }

    /** out = a * b; out may alias a or b. */
    public static void concat(float[] a, int ao, float[] b, int bo, float[] out, int oo) {
        float[] t = new float[12];
        for (int row = 0; row < 3; row++) {
            int ar = ao + row * 4;
            int tr = row * 4;
            t[tr] = a[ar] * b[bo] + a[ar + 1] * b[bo + 4] + a[ar + 2] * b[bo + 8];
            t[tr + 1] = a[ar] * b[bo + 1] + a[ar + 1] * b[bo + 5] + a[ar + 2] * b[bo + 9];
            t[tr + 2] = a[ar] * b[bo + 2] + a[ar + 1] * b[bo + 6] + a[ar + 2] * b[bo + 10];
            t[tr + 3] = a[ar] * b[bo + 3] + a[ar + 1] * b[bo + 7] + a[ar + 2] * b[bo + 11]
                      + a[ar + 3];
        }
        System.arraycopy(t, 0, out, oo, 12);
    }

    /** One forward pass works because MDL bones are stored parents first. */
    public static void buildMatrices(MdlFile mdl, float[] pos, float[] rot, float[] boneToWorld) {
        for (MdlFile.Bone b : mdl.bones) {
            int i = b.index;
            float[] local = new float[12];
            quatToMatrix(rot, i * 4, pos, i * 3, local, 0);

            if (b.parent < 0) {
                System.arraycopy(local, 0, boneToWorld, i * 12, 12);
            } else {
                concat(boneToWorld, b.parent * 12, local, 0, boneToWorld, i * 12);
            }
        }
    }

    public static void buildPalette(MdlFile mdl, float[] boneToWorld, float[] palette) {
        for (MdlFile.Bone b : mdl.bones) {
            concat(boneToWorld, b.index * 12, b.poseToBone, 0, palette, b.index * 12);
        }
    }

    public static void skinPosition(float[] palette, VvdFile.Vertex v, float[] out) {
        if (v.numBones <= 1) {
            transform(palette, v.bone0 * 12, v.px, v.py, v.pz, out);
            return;
        }
        final float x = v.px, y = v.py, z = v.pz;
        float ax = 0f, ay = 0f, az = 0f;
        for (int k = 0; k < v.numBones; k++) {
            int bone = k == 0 ? v.bone0 : k == 1 ? v.bone1 : v.bone2;
            float w = k == 0 ? v.weight0 : k == 1 ? v.weight1 : v.weight2;
            if (w == 0f) continue;
            int o = bone * 12;
            ax += (palette[o] * x + palette[o + 1] * y + palette[o + 2] * z + palette[o + 3]) * w;
            ay += (palette[o + 4] * x + palette[o + 5] * y + palette[o + 6] * z + palette[o + 7]) * w;
            az += (palette[o + 8] * x + palette[o + 9] * y + palette[o + 10] * z + palette[o + 11]) * w;
        }
        out[0] = ax; out[1] = ay; out[2] = az;
    }

    public static void skinNormal(float[] palette, VvdFile.Vertex v, float[] out) {
        if (v.numBones <= 1) {
            rotate(palette, v.bone0 * 12, v.nx, v.ny, v.nz, out);
            return;
        }
        final float x = v.nx, y = v.ny, z = v.nz;
        float ax = 0f, ay = 0f, az = 0f;
        for (int k = 0; k < v.numBones; k++) {
            int bone = k == 0 ? v.bone0 : k == 1 ? v.bone1 : v.bone2;
            float w = k == 0 ? v.weight0 : k == 1 ? v.weight1 : v.weight2;
            if (w == 0f) continue;
            int o = bone * 12;
            ax += (palette[o] * x + palette[o + 1] * y + palette[o + 2] * z) * w;
            ay += (palette[o + 4] * x + palette[o + 5] * y + palette[o + 6] * z) * w;
            az += (palette[o + 8] * x + palette[o + 9] * y + palette[o + 10] * z) * w;
        }
        float n = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (n > 1e-6f) {
            ax /= n; ay /= n; az /= n;
        }
        out[0] = ax; out[1] = ay; out[2] = az;
    }

    private static void transform(float[] m, int o, float x, float y, float z, float[] out) {
        out[0] = m[o] * x + m[o + 1] * y + m[o + 2] * z + m[o + 3];
        out[1] = m[o + 4] * x + m[o + 5] * y + m[o + 6] * z + m[o + 7];
        out[2] = m[o + 8] * x + m[o + 9] * y + m[o + 10] * z + m[o + 11];
    }

    private static void rotate(float[] m, int o, float x, float y, float z, float[] out) {
        out[0] = m[o] * x + m[o + 1] * y + m[o + 2] * z;
        out[1] = m[o + 4] * x + m[o + 5] * y + m[o + 6] * z;
        out[2] = m[o + 8] * x + m[o + 9] * y + m[o + 10] * z;
    }
}
