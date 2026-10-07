package com.soldiermc.studio;

/** Sequence blending; a grid cell blends three corners, as with anim_3wayblend 1. */
public final class SequenceEvaluator {

    private final MdlFile mdl;
    private final AnimSampler sampler;

    private final float[] posA = new float[86 * 3];
    private final float[] rotA = new float[86 * 4];
    private final float[] posB = new float[86 * 3];
    private final float[] rotB = new float[86 * 4];
    private final float[] q1 = new float[4];
    private final float[] q2 = new float[4];
    private final float[] qo = new float[4];

    private final float[] layerPos = new float[86 * 3];
    private final float[] layerRot = new float[86 * 4];

    private final PoseAxis axis0 = new PoseAxis();
    private final PoseAxis axis1 = new PoseAxis();
    private final int[] corners = new int[3];
    private final float[] weights = new float[3];

    private static final class PoseAxis {
        int index;
        float setting;
    }

    public SequenceEvaluator(MdlFile mdl, AnimSampler sampler) {
        this.mdl = mdl;
        this.sampler = sampler;
    }

    public void evaluate(MdlFile.SeqDesc seq, float cycle, float poseX, float poseY,
                         float[] outPos, float[] outRot) {
        if (outPos == posA || outPos == posB || outRot == rotA || outRot == rotB) {
            throw new IllegalArgumentException(
                    "evaluate() output aliases its own scratch buffers, so the blend would be a no-op");
        }
        if (!seq.isBlend()) {
            sampleAnim(seq.blends[0], cycle, outPos, outRot);
            return;
        }

        localPoseParameter(seq, 0, poseX, axis0);
        localPoseParameter(seq, 1, poseY, axis1);
        final int i0 = axis0.index;
        final int i1 = axis1.index;
        final float s0 = axis0.setting;
        final float s1 = axis1.setting;

        // A one-cell axis gets setting 0, so these branches never index past the grid.
        if (s0 < 0.001f) {
            if (s1 < 0.001f) {
                sampleAnim(idx(seq, i0, i1), cycle, outPos, outRot);
            } else if (s1 > 0.999f) {
                sampleAnim(idx(seq, i0, i1 + 1), cycle, outPos, outRot);
            } else {
                sampleAnim(idx(seq, i0, i1), cycle, outPos, outRot);
                sampleAnim(idx(seq, i0, i1 + 1), cycle, posA, rotA);
                blendInto(outPos, outRot, posA, rotA, s1);
            }
            return;
        }
        if (s0 > 0.999f) {
            if (s1 < 0.001f) {
                sampleAnim(idx(seq, i0 + 1, i1), cycle, outPos, outRot);
            } else if (s1 > 0.999f) {
                sampleAnim(idx(seq, i0 + 1, i1 + 1), cycle, outPos, outRot);
            } else {
                sampleAnim(idx(seq, i0 + 1, i1), cycle, outPos, outRot);
                sampleAnim(idx(seq, i0 + 1, i1 + 1), cycle, posA, rotA);
                blendInto(outPos, outRot, posA, rotA, s1);
            }
            return;
        }
        if (s1 < 0.001f) {
            sampleAnim(idx(seq, i0, i1), cycle, outPos, outRot);
            sampleAnim(idx(seq, i0 + 1, i1), cycle, posA, rotA);
            blendInto(outPos, outRot, posA, rotA, s0);
            return;
        }
        if (s1 > 0.999f) {
            sampleAnim(idx(seq, i0, i1 + 1), cycle, outPos, outRot);
            sampleAnim(idx(seq, i0 + 1, i1 + 1), cycle, posA, rotA);
            blendInto(outPos, outRot, posA, rotA, s0);
            return;
        }

        calc3WayBlendIndices(seq, i0, i1, s0, s1, corners, weights);

        if (weights[1] < 0.001f) {
            sampleAnim(corners[0], cycle, outPos, outRot);
            sampleAnim(corners[2], cycle, posA, rotA);
            blendInto(outPos, outRot, posA, rotA, weights[2] / (weights[0] + weights[2]));
        } else {
            sampleAnim(corners[0], cycle, outPos, outRot);
            sampleAnim(corners[1], cycle, posA, rotA);
            blendInto(outPos, outRot, posA, rotA, weights[1] / (weights[0] + weights[1]));
            sampleAnim(corners[2], cycle, posB, rotB);
            blendInto(outPos, outRot, posB, rotB, weights[2]);
        }
    }

    private void calc3WayBlendIndices(MdlFile.SeqDesc seq, int i0, int i1, float s0, float s1,
                                      int[] outCorners, float[] outWeights) {
        int x1, y1, x2, y2, x3, y3;

        if (((i0 + i1) & 1) == 0) {
            if (s0 > s1) {
                x1 = 0; y1 = 0; x2 = 1; y2 = 0; x3 = 1; y3 = 1;
                outWeights[0] = 1.0f - s0;
                outWeights[1] = s0 - s1;
            } else {
                x1 = 1; y1 = 1; x2 = 0; y2 = 1; x3 = 0; y3 = 0;
                outWeights[0] = s0;
                outWeights[1] = s1 - s0;
            }
        } else {
            if (s0 + s1 > 1.0f) {
                x1 = 1; y1 = 0; x2 = 1; y2 = 1; x3 = 0; y3 = 1;
                outWeights[0] = 1.0f - s1;
                outWeights[1] = s0 - 1.0f + s1;
            } else {
                x1 = 0; y1 = 1; x2 = 0; y2 = 0; x3 = 1; y3 = 0;
                outWeights[0] = s1;
                outWeights[1] = 1.0f - s0 - s1;
            }
        }

        outCorners[0] = idx(seq, i0 + x1, i1 + y1);
        outCorners[1] = idx(seq, i0 + x2, i1 + y2);
        outCorners[2] = idx(seq, i0 + x3, i1 + y3);

        if (outWeights[1] < 0.001f) outWeights[1] = 0f;
        outWeights[2] = 1.0f - outWeights[0] - outWeights[1];
    }

    public void applyAutoLayers(MdlFile.SeqDesc base, float bodyYaw, float bodyPitch,
                                float[] pos, float[] rot) {
        for (MdlFile.AutoLayer al : base.autoLayers) {
            if (al.sequence < 0 || al.sequence >= mdl.sequences.length) continue;
            MdlFile.SeqDesc layer = mdl.sequences[al.sequence];

            evaluate(layer, 0f, bodyYaw, bodyPitch, layerPos, layerRot);

            for (int b = 0; b < mdl.bones.length; b++) {
                float w = layer.boneWeights != null ? layer.boneWeights[b] : 1.0f;
                if (w <= 0f) continue;

                int p3 = b * 3, r4 = b * 4;
                pos[p3] += layerPos[p3] * w;
                pos[p3 + 1] += layerPos[p3 + 1] * w;
                pos[p3 + 2] += layerPos[p3 + 2] * w;

                System.arraycopy(rot, r4, q1, 0, 4);
                System.arraycopy(layerRot, r4, q2, 0, 4);
                AnimDecode.quatMA(q1, w, q2, qo);
                System.arraycopy(qo, 0, rot, r4, 4);
            }
        }
    }

    private final float[] gesturePos = new float[86 * 3];
    private final float[] gestureRot = new float[86 * 4];
    private final float[] armPos = new float[86 * 3];
    private final float[] armRot = new float[86 * 4];

    /** Run after applyAutoLayers: quaternion deltas do not commute. */
    public void accumulateGesture(MdlFile.SeqDesc seq, float cycle, float weight,
                                  float[] pos, float[] rot) {
        if (seq == null || weight <= 0f) return;

        evaluate(seq, cycle, 0f, 0f, gesturePos, gestureRot);

        mergeInto(seq, gesturePos, gestureRot, weight, pos, rot);

        // As in AccumulatePose, non-local autolayers merge into the accumulator.
        for (MdlFile.AutoLayer al : seq.autoLayers) {
            if ((al.flags & 0x1000) != 0) continue;          // STUDIO_AL_LOCAL
            if (al.sequence < 0 || al.sequence >= mdl.sequences.length) continue;
            MdlFile.SeqDesc layer = mdl.sequences[al.sequence];
            evaluate(layer, cycle, 0f, 0f, armPos, armRot);
            mergeInto(layer, armPos, armRot, weight, pos, rot);
        }
    }

    private void mergeInto(MdlFile.SeqDesc src, float[] srcPos, float[] srcRot, float weight,
                           float[] pos, float[] rot) {
        boolean delta = (src.flags & AnimDecode.STUDIO_DELTA) != 0;
        boolean post = (src.flags & 0x0010) != 0;   // STUDIO_POST

        for (int b = 0; b < mdl.bones.length; b++) {
            float w = weight * (src.boneWeights != null ? src.boneWeights[b] : 1.0f);
            if (w <= 0f) continue;

            int p3 = b * 3, r4 = b * 4;
            System.arraycopy(rot, r4, q1, 0, 4);
            System.arraycopy(srcRot, r4, q2, 0, 4);

            if (delta) {
                pos[p3] += srcPos[p3] * w;
                pos[p3 + 1] += srcPos[p3 + 1] * w;
                pos[p3 + 2] += srcPos[p3 + 2] * w;
                if (post) {
                    AnimDecode.quatMA(q1, w, q2, qo);
                } else {
                    AnimDecode.quatSM(w, q2, q1, qo);
                }
            } else {
                pos[p3] = pos[p3] * (1f - w) + srcPos[p3] * w;
                pos[p3 + 1] = pos[p3 + 1] * (1f - w) + srcPos[p3 + 1] * w;
                pos[p3 + 2] = pos[p3 + 2] * (1f - w) + srcPos[p3 + 2] * w;
                AnimDecode.quatBlend(q1, q2, w, qo);
            }
            System.arraycopy(qo, 0, rot, r4, 4);
        }
    }

    public float resolveAxis(MdlFile.SeqDesc seq, int localIndex, float raw) {
        PoseAxis a = localIndex == 0 ? axis0 : axis1;
        localPoseParameter(seq, localIndex, raw, a);
        return a.index + a.setting;
    }

    private void localPoseParameter(MdlFile.SeqDesc seq, int localIndex, float raw, PoseAxis out) {
        out.index = 0;
        out.setting = 0f;

        int paramIndex = localIndex == 0 ? seq.paramIndex0 : seq.paramIndex1;
        int groupSize = localIndex == 0 ? seq.groupSizeX : seq.groupSizeY;
        if (paramIndex < 0 || paramIndex >= mdl.poseParams.length || groupSize < 2) return;

        MdlFile.PoseParam pp = mdl.poseParams[paramIndex];
        float span = pp.end - pp.start;
        if (span == 0f) return;

        float ctl = clamp01((wrapLoop(pp, raw) - pp.start) / span);

        // Redundant on a 0..1 value, kept to match Studio_LocalPoseParameter.
        float v = wrapLoop(pp, ctl);

        if (seq.poseKeys == null) {
            float localStart = (paramStart(seq, localIndex) - pp.start) / span;
            float localEnd = (paramEnd(seq, localIndex) - pp.start) / span;
            float s = localEnd == localStart
                    ? 0f
                    : clamp01((v - localStart) / (localEnd - localStart));
            int index = 0;
            if (groupSize > 2) {
                index = (int) (s * (groupSize - 1));
                if (index == groupSize - 1) index = groupSize - 2;
                s = s * (groupSize - 1) - index;
            }
            out.index = index;
            out.setting = s;
        } else {
            float deg = v * span + pp.start;
            int index = 0;
            float s;
            while (true) {
                float k0 = seq.poseKey(localIndex, index);
                float k1 = seq.poseKey(localIndex, index + 1);
                s = k1 == k0 ? 0f : (deg - k0) / (k1 - k0);
                if (index < groupSize - 2 && s > 1.0f) {
                    index++;
                    continue;
                }
                break;
            }
            out.index = index;
            out.setting = clamp01(s);
        }
    }

    private static float wrapLoop(MdlFile.PoseParam pp, float v) {
        if (pp.loop == 0f) return v;
        float wrap = (pp.start + pp.end) / 2.0f + pp.loop / 2.0f;
        float shift = pp.loop - wrap;
        return v - pp.loop * (float) Math.floor((v + shift) / pp.loop);
    }

    private static float paramStart(MdlFile.SeqDesc seq, int localIndex) {
        return localIndex == 0 ? seq.paramStart0 : seq.paramStart1;
    }

    private static float paramEnd(MdlFile.SeqDesc seq, int localIndex) {
        return localIndex == 0 ? seq.paramEnd0 : seq.paramEnd1;
    }

    private int idx(MdlFile.SeqDesc seq, int x, int y) {
        return seq.blends[y * seq.groupSizeX + x];
    }

    private void sampleAnim(int animIndex, float cycle, float[] pos, float[] rot) {
        if (animIndex < 0 || animIndex >= mdl.animDescs.length) return;
        AnimSampler.AnimDesc ad = mdl.animDescs[animIndex];
        float exact = clamp01(cycle) * Math.max(ad.numFrames - 1, 0);
        int frame = (int) exact;
        sampler.sample(ad, frame, exact - frame, pos, rot);
    }

    public static void blendSkeleton(int boneCount, float[] aPos, float[] aRot,
                                     float[] bPos, float[] bRot, float w) {
        if (w <= 0f) return;
        float s = Math.min(w, 1f);
        float[] x = new float[4];
        float[] y = new float[4];
        float[] o = new float[4];
        for (int b = 0; b < boneCount; b++) {
            int p3 = b * 3, r4 = b * 4;
            aPos[p3] += (bPos[p3] - aPos[p3]) * s;
            aPos[p3 + 1] += (bPos[p3 + 1] - aPos[p3 + 1]) * s;
            aPos[p3 + 2] += (bPos[p3 + 2] - aPos[p3 + 2]) * s;
            System.arraycopy(aRot, r4, x, 0, 4);
            System.arraycopy(bRot, r4, y, 0, 4);
            AnimDecode.quatBlend(x, y, s, o);
            System.arraycopy(o, 0, aRot, r4, 4);
        }
    }

    private void blendInto(float[] pos, float[] rot, float[] srcPos, float[] srcRot, float w) {
        if (w <= 0f) return;
        float s = Math.min(w, 1f);
        for (int b = 0; b < mdl.bones.length; b++) {
            int p3 = b * 3, r4 = b * 4;
            pos[p3] += (srcPos[p3] - pos[p3]) * s;
            pos[p3 + 1] += (srcPos[p3 + 1] - pos[p3 + 1]) * s;
            pos[p3 + 2] += (srcPos[p3 + 2] - pos[p3 + 2]) * s;

            System.arraycopy(rot, r4, q1, 0, 4);
            System.arraycopy(srcRot, r4, q2, 0, 4);
            AnimDecode.quatBlend(q1, q2, s, qo);
            System.arraycopy(qo, 0, rot, r4, 4);
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
