package com.soldiermc.studio;

import static com.soldiermc.studio.AnimDecode.*;

/** CalcBoneQuaternion and CalcBonePosition from bone_setup.cpp. */
public final class AnimSampler {

    private final Reader r;
    private final MdlFile mdl;

    private final float[] scratch2 = new float[2];
    private final float[] q1 = new float[4];
    private final float[] q2 = new float[4];

    public AnimSampler(Reader reader, MdlFile mdl) {
        this.r = reader;
        this.mdl = mdl;
    }

    public void sample(AnimDesc ad, int frame, float s, float[] pos, float[] rot) {
        boolean delta = (ad.flags & AnimDecode.STUDIO_DELTA) != 0;
        for (MdlFile.Bone b : mdl.bones) {
            int i = b.index;
            if (delta) {
                pos[i * 3] = 0f;
                pos[i * 3 + 1] = 0f;
                pos[i * 3 + 2] = 0f;
                rot[i * 4] = 0f;
                rot[i * 4 + 1] = 0f;
                rot[i * 4 + 2] = 0f;
                rot[i * 4 + 3] = 1f;
            } else {
                pos[i * 3] = b.pos[0];
                pos[i * 3 + 1] = b.pos[1];
                pos[i * 3 + 2] = b.pos[2];
                rot[i * 4] = b.quat[0];
                rot[i * 4 + 1] = b.quat[1];
                rot[i * 4 + 2] = b.quat[2];
                rot[i * 4 + 3] = b.quat[3];
            }
        }
        if ((ad.flags & STUDIO_ALLZEROS) != 0) return;

        int off = ad.animOffset;
        int localFrame = frame;
        if (ad.sectionFrames != 0) {
            int isec = frame / ad.sectionFrames;
            int so = ad.structBase + ad.sectionIndex + isec * 8;
            int animBlock = r.i32At(so);
            int animIndex = r.i32At(so + 4);
            if (animBlock != 0) return;   // data lives in an external .ani block
            off = ad.structBase + animIndex;
            localFrame = frame - isec * ad.sectionFrames;
        }

        while (true) {
            int bone = r.u8At(off);
            int flags = r.u8At(off + 1);
            int next = r.i16At(off + 2);

            if (bone >= 0 && bone < mdl.bones.length) {
                // In a delta animdesc every track is a delta, whatever its own flag says.
                int effective = delta ? (flags | STUDIO_ANIM_DELTA) : flags;
                calcQuaternion(off, effective, localFrame, s, mdl.bones[bone], rot, bone);
                calcPosition(off, effective, localFrame, s, mdl.bones[bone], pos, bone);
            }
            if (next == 0) break;
            off += next;
        }
    }

    private void calcQuaternion(int off, int flags, int frame, float s,
                                MdlFile.Bone bone, float[] rot, int bi) {
        int data = off + 4;

        if ((flags & STUDIO_ANIM_RAWROT) != 0) {
            quat48(r, data, q1);
            store4(rot, bi, q1);
            return;
        }
        if ((flags & STUDIO_ANIM_RAWROT2) != 0) {
            quat64(r, data, q1);
            store4(rot, bi, q1);
            return;
        }
        if ((flags & STUDIO_ANIM_ANIMROT) == 0) {
            if ((flags & STUDIO_ANIM_DELTA) != 0) {
                rot[bi * 4] = 0f; rot[bi * 4 + 1] = 0f; rot[bi * 4 + 2] = 0f; rot[bi * 4 + 3] = 1f;
            }
            return;
        }

        int vp = data;
        float[] a1 = new float[3];
        float[] a2 = new float[3];
        for (int j = 0; j < 3; j++) {
            int rel = r.i16At(vp + j * 2);
            int base = rel > 0 ? vp + rel : 0;
            extractAnimValue(r, base, frame, bone.rotScale[j], scratch2);
            a1[j] = scratch2[0];
            a2[j] = scratch2[1];
        }
        if ((flags & STUDIO_ANIM_DELTA) == 0) {
            for (int j = 0; j < 3; j++) {
                a1[j] += referenceRot(bone, j);
                a2[j] += referenceRot(bone, j);
            }
        }
        angleQuaternion(a1[0], a1[1], a1[2], q1);
        if (s > 0.001f && (a1[0] != a2[0] || a1[1] != a2[1] || a1[2] != a2[2])) {
            angleQuaternion(a2[0], a2[1], a2[2], q2);
            float[] blended = new float[4];
            quatBlend(q1, q2, s, blended);
            store4(rot, bi, blended);
        } else {
            store4(rot, bi, q1);
        }
    }

    private void calcPosition(int off, int flags, int frame, float s,
                              MdlFile.Bone bone, float[] pos, int bi) {
        if ((flags & STUDIO_ANIM_RAWPOS) != 0) {
            float[] v = new float[3];
            vector48(r, posPtr(off, flags), v);
            pos[bi * 3] = v[0]; pos[bi * 3 + 1] = v[1]; pos[bi * 3 + 2] = v[2];
            return;
        }
        if ((flags & STUDIO_ANIM_ANIMPOS) == 0) {
            if ((flags & STUDIO_ANIM_DELTA) != 0) {
                pos[bi * 3] = 0f; pos[bi * 3 + 1] = 0f; pos[bi * 3 + 2] = 0f;
            }
            return;
        }

        int vp = off + 4 + (((flags & STUDIO_ANIM_ANIMROT) != 0) ? 6 : 0);
        for (int j = 0; j < 3; j++) {
            int rel = r.i16At(vp + j * 2);
            int base = rel > 0 ? vp + rel : 0;
            extractAnimValue(r, base, frame, bone.posScale[j], scratch2);
            float v = s > 0.001f
                    ? scratch2[0] * (1.0f - s) + scratch2[1] * s
                    : scratch2[0];
            if ((flags & STUDIO_ANIM_DELTA) == 0) v += bone.pos[j];
            pos[bi * 3 + j] = v;
        }
    }

    private static int posPtr(int off, int flags) {
        int p = off + 4;
        if ((flags & STUDIO_ANIM_RAWROT) != 0) p += 6;
        if ((flags & STUDIO_ANIM_RAWROT2) != 0) p += 8;
        if ((flags & STUDIO_ANIM_ANIMROT) != 0) p += 6;
        if ((flags & STUDIO_ANIM_ANIMPOS) != 0) p += 6;
        return p;
    }

    private float referenceRot(MdlFile.Bone bone, int axis) {
        return bone.refRot[axis];
    }

    private static void store4(float[] dst, int bi, float[] q) {
        dst[bi * 4] = q[0];
        dst[bi * 4 + 1] = q[1];
        dst[bi * 4 + 2] = q[2];
        dst[bi * 4 + 3] = q[3];
    }

    public static final class AnimDesc {
        public int structBase;
        public String name;
        public float fps;
        public int flags;
        public int numFrames;
        public int animOffset;      // absolute, unlike sectionIndex
        public int sectionIndex;
        public int sectionFrames;

        public float duration() {
            return fps > 0 ? (numFrames - 1) / fps : 0f;
        }
    }
}
