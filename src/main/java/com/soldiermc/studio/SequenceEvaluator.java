package com.soldiermc.studio;

/**
 * Evaluates a sequence at a cycle and a pair of pose parameters, blending its animation grid. A grid
 * is a barycentric blend of three corners, not a 2×2 bilinear one ({@code anim_3wayblend} defaults to
 * 1 here). Pose parameters resolve in two stages: {@code Studio_SetPoseParameter} turns degrees into
 * a clamped {@code 0..1} control value, {@code Studio_LocalPoseParameter} maps that onto the
 * sequence's own grid, either through {@code paramstart/paramend} or through a pose-key table.
 */
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

    /** Output buffers for an autolayer. {@link #evaluate} uses posA/rotA as scratch, so its output must not alias them. */
    private final float[] layerPos = new float[86 * 3];
    private final float[] layerRot = new float[86 * 4];

    private final PoseAxis axis0 = new PoseAxis();
    private final PoseAxis axis1 = new PoseAxis();
    private final int[] corners = new int[3];
    private final float[] weights = new float[3];

    /** {@code Studio_LocalPoseParameter}'s two outputs: which cell, and how far across it. */
    private static final class PoseAxis {
        int index;
        float setting;
    }

    public SequenceEvaluator(MdlFile mdl, AnimSampler sampler) {
        this.mdl = mdl;
        this.sampler = sampler;
    }

    /**
     * @param cycle 0..1 through the sequence
     * @param poseX raw value, in the pose parameter's own units, for {@code paramIndex0}
     * @param poseY raw value for {@code paramIndex1}
     */
    public void evaluate(MdlFile.SeqDesc seq, float cycle, float poseX, float poseY,
                         float[] outPos, float[] outRot) {
        // Aliasing the output onto the scratch would turn every blend into a no-op.
        if (outPos == posA || outPos == posB || outRot == rotA || outRot == rotB) {
            throw new IllegalArgumentException(
                    "evaluate() output aliases its own scratch buffers — the blend would be a no-op");
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

        // CalcPoseSingle's edge cases. An axis with fewer than two cells resolves to (0, 0), so
        // these branches also keep the i+1 lookups inside the grid.
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
            // On the diagonal, so the middle corner drops out entirely.
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

    /**
     * {@code Calc3WayBlendIndices}. The unit cell splits into two triangles and which diagonal is
     * used alternates with cell parity: an even cell splits on {@code s0 > s1}, an odd one on
     * {@code s0 + s1 > 1}.
     */
    private void calc3WayBlendIndices(MdlFile.SeqDesc seq, int i0, int i1, float s0, float s1,
                                      int[] outCorners, float[] outWeights) {
        int x1, y1, x2, y2, x3, y3;

        if (((i0 + i1) & 1) == 0) {          // diagonal runs top-left to bottom-right
            if (s0 > s1) {
                x1 = 0; y1 = 0; x2 = 1; y2 = 0; x3 = 1; y3 = 1;
                outWeights[0] = 1.0f - s0;
                outWeights[1] = s0 - s1;
            } else {
                x1 = 1; y1 = 1; x2 = 0; y2 = 1; x3 = 0; y3 = 0;
                outWeights[0] = s0;
                outWeights[1] = s1 - s0;
            }
        } else {                             // diagonal runs bottom-left to top-right
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

    /**
     * Apply a sequence's autolayers as additive deltas on top of an already-evaluated pose. The aim
     * layer is a 3×4 grid of one-frame {@code STUDIO_DELTA} clips selected by
     * {@code body_yaw}/{@code body_pitch}, whose own weight list zeroes the eight leg bones.
     *
     * @param bodyYaw   degrees, already negated by the caller per {@code ComputePoseParam_AimYaw}
     * @param bodyPitch degrees, already negated per {@code ComputePoseParam_AimPitch}
     */
    public void applyAutoLayers(MdlFile.SeqDesc base, float bodyYaw, float bodyPitch,
                                float[] pos, float[] rot) {
        for (MdlFile.AutoLayer al : base.autoLayers) {
            if (al.sequence < 0 || al.sequence >= mdl.sequences.length) continue;
            MdlFile.SeqDesc layer = mdl.sequences[al.sequence];

            // One frame per cell, so cycle is irrelevant. paramIndex0 is body_yaw and paramIndex1 is
            // body_pitch, hence this argument order; the locomotion grids cross their axes the other way.
            evaluate(layer, 0f, bodyYaw, bodyPitch, layerPos, layerRot);

            for (int b = 0; b < mdl.bones.length; b++) {
                float w = layer.boneWeights != null ? layer.boneWeights[b] : 1.0f;
                if (w <= 0f) continue;      // the eight leg bones land here

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

    /**
     * {@code AccumulateLayers} — accumulate one gesture layer on top of an already-posed skeleton.
     * Runs after the aim matrix, since post-multiplication does not commute.
     *
     * <p>Weight is a hard 1.0 with no ramp: {@code C_AnimationLayer::BlendWeight} returns early with
     * {@code m_bClientBlend} unset, and {@code m_flBlendIn}/{@code m_flBlendOut} and
     * {@code m_flLayerFadeOuttime} are all 0 for a TF player. Per-bone shaping comes from the
     * sequence's own weight list as {@code SlerpBones} does it,
     * {@code s2 = layerWeight * seqdesc.weight(bone)}.
     */
    public void accumulateGesture(MdlFile.SeqDesc seq, float cycle, float weight,
                                  float[] pos, float[] rot) {
        if (seq == null || weight <= 0f) return;

        // CalcPoseSingle -> the gesture's own pose, in a local buffer.
        evaluate(seq, cycle, 0f, 0f, gesturePos, gestureRot);

        // SlerpBones -> merge that local pose into the accumulator.
        mergeInto(seq, gesturePos, gestureRot, weight, pos, rot);

        // AddSequenceLayers -> the autolayers go onto the accumulator, after the merge above, not
        // into the gesture's local buffer (AccumulatePose, bone_setup.cpp). Only layers flagged
        // STUDIO_AL_LOCAL take the local buffer, and none of the Soldier's do; the voice gestures'
        // left-arm layers are absolute (animflags 0x0000).
        for (MdlFile.AutoLayer al : seq.autoLayers) {
            if ((al.flags & 0x1000) != 0) continue;          // STUDIO_AL_LOCAL
            if (al.sequence < 0 || al.sequence >= mdl.sequences.length) continue;
            MdlFile.SeqDesc layer = mdl.sequences[al.sequence];
            // start == peak == tail == end == 0 on every one of these, so there is no envelope: the
            // layer runs at the parent's cycle and the parent's weight.
            evaluate(layer, cycle, 0f, 0f, armPos, armRot);
            mergeInto(layer, armPos, armRot, weight, pos, rot);
        }
    }

    /**
     * {@code SlerpBones} — merge one evaluated pose into an accumulator at
     * {@code s2 = weight * seqdesc.weight(bone)}. {@code DELTA|POST} multiplies the scaled delta on
     * the right, plain {@code DELTA} on the left, and an absolute sequence blends toward its pose.
     */
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

    /** Test and diagnostic hook: where one axis lands, as {@code cell + fraction}. */
    public float resolveAxis(MdlFile.SeqDesc seq, int localIndex, float raw) {
        PoseAxis a = localIndex == 0 ? axis0 : axis1;
        localPoseParameter(seq, localIndex, raw, a);
        return a.index + a.setting;
    }

    /**
     * {@code Studio_SetPoseParameter} followed by {@code Studio_LocalPoseParameter}. Source shifts by
     * {@code loop - ((start + end) / 2 + loop / 2)} before flooring, which makes the wrap a no-op
     * across the authored range. Both aim axes have {@code loop = 360}.
     */
    private void localPoseParameter(MdlFile.SeqDesc seq, int localIndex, float raw, PoseAxis out) {
        out.index = 0;
        out.setting = 0f;

        int paramIndex = localIndex == 0 ? seq.paramIndex0 : seq.paramIndex1;
        int groupSize = localIndex == 0 ? seq.groupSizeX : seq.groupSizeY;
        if (paramIndex < 0 || paramIndex >= mdl.poseParams.length || groupSize < 2) return;

        MdlFile.PoseParam pp = mdl.poseParams[paramIndex];
        float span = pp.end - pp.start;
        if (span == 0f) return;

        // Stage 1 — Studio_SetPoseParameter: the entity stores a clamped 0..1 control value.
        float ctl = clamp01((wrapLoop(pp, raw) - pp.start) / span);

        // Stage 2 — Studio_LocalPoseParameter reads it back. The second wrap is a no-op on a 0..1
        // value, and is kept because Source does it.
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
            // The pose-key path, taken by the aim matrix. Its keys descend — yaw 45, 0, -45 and
            // pitch 90, 45, 0, -45 — so a positive body_yaw selects the rightmost column.
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

    /** {@code SlerpBones}-style weighted blend of one skeleton toward another, in place on {@code a}. */
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

    /** {@code SlerpBones}-style weighted blend of a whole skeleton. */
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
