package com.soldiermc.studio;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The animation chain: RLE decode → forward kinematics → poseToBone → skinning. */
class AnimationTest {

    private static final Path DIR = Path.of("tf2-assets-staging/models/player");

    private static byte[] read(String name) {
        try {
            Path p = DIR.resolve(name);
            Assumptions.assumeTrue(Files.exists(p), "extracted assets absent — skipping");
            return Files.readAllBytes(p);
        } catch (Exception e) {
            Assumptions.abort("could not read " + name + ": " + e);
            return null;
        }
    }

    @Test
    @DisplayName("858 animations parse with real names, fps and flags")
    void animDescTable() {
        MdlFile anims = new MdlFile(read("soldier_animations.mdl"));
        assertEquals(858, anims.animDescs.length);

        AnimSampler.AnimDesc stand = anims.findAnim("@stand_PRIMARY");
        assertNotNull(stand, "the base standing idle must be present");
        assertEquals(30.0f, stand.fps, 0.01f);
        assertEquals(51, stand.numFrames);
        assertTrue((stand.flags & AnimDecode.STUDIO_LOOPING) != 0, "the idle loops");

        // The attack and reload animations are additive deltas, layered onto a base pose rather
        // than played standalone.
        AnimSampler.AnimDesc attack = anims.findAnim("@AttackStand_PRIMARY");
        assertNotNull(attack);
        assertTrue((attack.flags & AnimDecode.STUDIO_DELTA) != 0,
                "@AttackStand_PRIMARY must be flagged STUDIO_DELTA");
    }

    @Test
    @DisplayName("skinning @stand_PRIMARY puts the Soldier upright on the ground, in GAME space")
    void skinnedPoseIsUprightInGameSpace() {
        byte[] mdlBytes = read("soldier.mdl");
        MdlFile mdl = new MdlFile(mdlBytes);
        VvdFile vvd = new VvdFile(read("soldier.vvd"));
        VtxFile vtx = new VtxFile(read("soldier.dx90.vtx"));
        StudioModel model = new StudioModel(mdl, vvd, vtx, 0);

        byte[] animBytes = read("soldier_animations.mdl");
        MdlFile anims = new MdlFile(animBytes);
        AnimSampler.AnimDesc stand = anims.findAnim("@stand_PRIMARY");

        AnimSampler sampler = new AnimSampler(new Reader(animBytes), anims);
        float[] pos = new float[86 * 3];
        float[] rot = new float[86 * 4];
        sampler.sample(stand, 12, 0f, pos, rot);

        float[] boneToWorld = new float[86 * 12];
        float[] palette = new float[86 * 12];
        Skeleton.buildMatrices(anims, pos, rot, boneToWorld);
        // The palette must use the mesh model's poseToBone, not the animation model's.
        Skeleton.buildPalette(mdl, boneToWorld, palette);

        float minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
        float[] o = new float[3];
        for (VvdFile.Vertex v : model.vertices) {
            Skeleton.skinPosition(palette, v, o);
            minZ = Math.min(minZ, o[2]);
            maxZ = Math.max(maxZ, o[2]);
            maxX = Math.max(maxX, o[0]);
        }

        // Game space is Z-up; file space is Y-up.
        assertTrue(minZ > -2.0f && minZ < 2.0f,
                "feet should sit near z=0, got minZ=" + minZ);
        assertTrue(maxZ > 60f && maxZ < 85f,
                "head should be roughly 70 hu up, got maxZ=" + maxZ);

        // 39.08, from the sequence's own bbox for this pose.
        assertEquals(39.0f, maxX, 1.5f, "forward reach of the posed arms");
    }

    @Test
    @DisplayName("a DELTA animation seeds unmentioned bones to IDENTITY, not the bind pose")
    void deltaAnimationsSeedIdentity() {
        byte[] animBytes = read("soldier_animations.mdl");
        MdlFile anims = new MdlFile(animBytes);
        AnimSampler sampler = new AnimSampler(new Reader(animBytes), anims);

        AnimSampler.AnimDesc attack = anims.findAnim("@AttackStand_PRIMARY");
        assertTrue((attack.flags & AnimDecode.STUDIO_DELTA) != 0, "precondition: it is a delta");

        float[] pos = new float[86 * 3];
        float[] rot = new float[86 * 4];
        sampler.sample(attack, 6, 0f, pos, rot);

        // The aim/attack deltas touch the upper body only. The result is added to a base pose, so
        // every bone the stream does not mention comes back as zero translation and unit rotation.
        int identityBones = 0;
        for (int b = 0; b < anims.bones.length; b++) {
            boolean zeroPos = Math.abs(pos[b * 3]) < 1e-4f
                    && Math.abs(pos[b * 3 + 1]) < 1e-4f
                    && Math.abs(pos[b * 3 + 2]) < 1e-4f;
            boolean unitRot = Math.abs(rot[b * 4 + 3] - 1f) < 1e-4f
                    && Math.abs(rot[b * 4]) < 1e-4f
                    && Math.abs(rot[b * 4 + 1]) < 1e-4f
                    && Math.abs(rot[b * 4 + 2]) < 1e-4f;
            if (zeroPos && unitRot) identityBones++;
        }
        assertTrue(identityBones > 40,
                "expected most of the 86 bones to be identity in a delta, got " + identityBones
                        + " — the delta is being seeded with the bind pose");
    }

    /**
     * The aim matrix hanging off the standing idle: a 3×4 grid whose columns are right/centre/left
     * and whose rows are straight-up/up/mid/down.
     */
    private static MdlFile.SeqDesc aimMatrix(MdlFile anims) {
        MdlFile.SeqDesc stand = anims.findSequence("stand_PRIMARY");
        assertNotNull(stand, "stand_PRIMARY must be present");
        assertTrue(stand.autoLayers.length > 0, "the idle must carry an aim matrix autolayer");
        MdlFile.SeqDesc layer = anims.sequences[stand.autoLayers[0].sequence];
        assertEquals(3, layer.groupSizeX, "aim matrix is 3 columns of yaw");
        assertEquals(4, layer.groupSizeY, "aim matrix is 4 rows of pitch");
        assertNotNull(layer.poseKeys, "the aim matrix selects its cells by pose key, not by range");
        return layer;
    }

    @Test
    @DisplayName("aim pitch points where the player looks, not the opposite way")
    void aimPitchIsNotInverted() {
        MdlFile anims = new MdlFile(read("soldier_animations.mdl"));
        SequenceEvaluator eval = new SequenceEvaluator(anims,
                new AnimSampler(new Reader(read("soldier_animations.mdl")), anims));
        MdlFile.SeqDesc aim = aimMatrix(anims);

        // Rows descend 90, 45, 0, -45, so row 1 is "up", 2 is "mid" and 3 is "down". body_pitch is
        // -eyePitch, so looking down (Minecraft xRot positive) arrives here as a negative value.
        float down = eval.resolveAxis(aim, 1, -45f);
        float mid = eval.resolveAxis(aim, 1, 0f);
        float up = eval.resolveAxis(aim, 1, 45f);

        assertEquals(3.0f, down, 0.05f, "body_pitch -45 must land on the down row");
        assertEquals(2.0f, mid, 0.05f, "body_pitch 0 must land on the mid row");
        assertEquals(1.0f, up, 0.05f, "body_pitch +45 must land on the up row");
        assertTrue(down > mid && mid > up,
                "row index must fall as the pose value rises — if it climbs, the aim is inverted");
    }

    @Test
    @DisplayName("aim yaw is continuous through zero")
    void aimYawDoesNotJumpAtZero() {
        MdlFile anims = new MdlFile(read("soldier_animations.mdl"));
        SequenceEvaluator eval = new SequenceEvaluator(anims,
                new AnimSampler(new Reader(read("soldier_animations.mdl")), anims));
        MdlFile.SeqDesc aim = aimMatrix(anims);

        // body_yaw has loop = 360. Wrapping it without Source's shift term sends every negative
        // value a whole period upward, where it clamps to the far column.
        float justLeft = eval.resolveAxis(aim, 0, -0.5f);
        float justRight = eval.resolveAxis(aim, 0, 0.5f);
        assertTrue(Math.abs(justLeft - justRight) < 0.05f,
                "a 1 degree step across zero moved the aim by " + Math.abs(justLeft - justRight)
                        + " cells — the loop wrap has lost its shift term");

        // The axis runs right to left, because the pose keys descend.
        assertEquals(0.0f, eval.resolveAxis(aim, 0, 45f), 0.05f, "+45 is the right column");
        assertEquals(1.0f, eval.resolveAxis(aim, 0, 0f), 0.05f, "0 is the centre column");
        assertEquals(2.0f, eval.resolveAxis(aim, 0, -45f), 0.05f, "-45 is the left column");
    }

    @Test
    @DisplayName("aim resolution is monotonic across the whole sweep")
    void aimSweepIsMonotonic() {
        MdlFile anims = new MdlFile(read("soldier_animations.mdl"));
        SequenceEvaluator eval = new SequenceEvaluator(anims,
                new AnimSampler(new Reader(read("soldier_animations.mdl")), anims));
        MdlFile.SeqDesc aim = aimMatrix(anims);

        for (int axis = 0; axis <= 1; axis++) {
            float prev = eval.resolveAxis(aim, axis, 120f);
            for (float deg = 119f; deg >= -120f; deg -= 1f) {
                float now = eval.resolveAxis(aim, axis, deg);
                assertTrue(now >= prev - 1e-4f,
                        "axis " + axis + " went backwards at " + deg + " degrees");
                assertTrue(now - prev < 0.2f,
                        "axis " + axis + " jumped " + (now - prev) + " cells at " + deg
                                + " degrees — that is a visible snap");
                prev = now;
            }
        }
    }

    /** Sum of absolute differences between two skeletons — zero means identical poses. */
    private static float poseDistance(float[] aPos, float[] aRot, float[] bPos, float[] bRot) {
        float d = 0f;
        for (int i = 0; i < aPos.length; i++) d += Math.abs(aPos[i] - bPos[i]);
        for (int i = 0; i < aRot.length; i++) d += Math.abs(aRot[i] - bRot[i]);
        return d;
    }

    @Test
    @DisplayName("the aim matrix interpolates between cells instead of snapping to one")
    void aimMatrixInterpolates() {
        MdlFile anims = new MdlFile(read("soldier_animations.mdl"));
        SequenceEvaluator eval = new SequenceEvaluator(anims,
                new AnimSampler(new Reader(read("soldier_animations.mdl")), anims));
        MdlFile.SeqDesc aim = aimMatrix(anims);

        // Through applyAutoLayers, not evaluate() directly: only applyAutoLayers can hand evaluate()
        // its own scratch arrays as output.
        MdlFile.SeqDesc stand = anims.findSequence("stand_PRIMARY");
        assertNotNull(stand);
        assertNotNull(aim);

        // Pitch rows are keyed 90/45/0/-45, so -22.5 sits halfway between the mid and down rows.
        float[] midPos = new float[86 * 3], midRot = new float[86 * 4];
        float[] downPos = new float[86 * 3], downRot = new float[86 * 4];
        float[] halfPos = new float[86 * 3], halfRot = new float[86 * 4];
        eval.evaluate(stand, 0f, 0f, 0f, midPos, midRot);
        System.arraycopy(midPos, 0, downPos, 0, midPos.length);
        System.arraycopy(midRot, 0, downRot, 0, midRot.length);
        System.arraycopy(midPos, 0, halfPos, 0, midPos.length);
        System.arraycopy(midRot, 0, halfRot, 0, midRot.length);

        eval.applyAutoLayers(stand, 0f, 0f, midPos, midRot);
        eval.applyAutoLayers(stand, 0f, -45f, downPos, downRot);
        eval.applyAutoLayers(stand, 0f, -22.5f, halfPos, halfRot);

        float span = poseDistance(midPos, midRot, downPos, downRot);
        assertTrue(span > 0.01f, "the mid and down aim rows must differ at all");

        float toMid = poseDistance(halfPos, halfRot, midPos, midRot);
        float toDown = poseDistance(halfPos, halfRot, downPos, downRot);

        assertTrue(toMid > 0.01f * span,
                "halfway pitch returned the mid row unchanged — the grid blend is a no-op");
        assertTrue(toDown > 0.01f * span,
                "halfway pitch returned the down row unchanged — the grid blend is a no-op");
    }

    @Test
    @DisplayName("evaluate refuses an output that aliases its own scratch buffers")
    void evaluateRejectsAliasedOutput() {
        MdlFile anims = new MdlFile(read("soldier_animations.mdl"));
        SequenceEvaluator eval = new SequenceEvaluator(anims,
                new AnimSampler(new Reader(read("soldier_animations.mdl")), anims));
        MdlFile.SeqDesc aim = aimMatrix(anims);

        float[] pos = new float[86 * 3];
        float[] rot = new float[86 * 4];
        eval.evaluate(aim, 0f, 0f, 0f, pos, rot);   // a clean call must still work

        java.lang.reflect.Field f;
        try {
            f = SequenceEvaluator.class.getDeclaredField("posA");
            f.setAccessible(true);
            float[] scratch = (float[]) f.get(eval);
            assertThrows(IllegalArgumentException.class,
                    () -> eval.evaluate(aim, 0f, 0f, 0f, scratch, rot),
                    "passing the evaluator's own scratch as output must fail loudly");
        } catch (ReflectiveOperationException e) {
            Assumptions.abort("posA field not reachable: " + e);
        }
    }

    @Test
    @DisplayName("the aim layer moves the upper body but never the legs")
    void aimLayerLeavesLegsAlone() {
        byte[] bytes = read("soldier_animations.mdl");
        MdlFile anims = new MdlFile(bytes);
        SequenceEvaluator eval = new SequenceEvaluator(anims, new AnimSampler(new Reader(bytes), anims));
        MdlFile.SeqDesc stand = anims.findSequence("stand_PRIMARY");
        assertNotNull(stand);

        float[] levelPos = new float[86 * 3], levelRot = new float[86 * 4];
        float[] upPos = new float[86 * 3], upRot = new float[86 * 4];
        eval.evaluate(stand, 0f, 0f, 0f, levelPos, levelRot);
        System.arraycopy(levelPos, 0, upPos, 0, levelPos.length);
        System.arraycopy(levelRot, 0, upRot, 0, levelRot.length);

        eval.applyAutoLayers(stand, 0f, 0f, levelPos, levelRot);
        eval.applyAutoLayers(stand, 0f, 40f, upPos, upRot);   // body_pitch +40 = looking up

        int moved = 0, legsMoved = 0;
        MdlFile.SeqDesc layer = anims.sequences[stand.autoLayers[0].sequence];
        for (int b = 0; b < anims.bones.length; b++) {
            float d = 0f;
            for (int k = 0; k < 4; k++) d += Math.abs(levelRot[b * 4 + k] - upRot[b * 4 + k]);
            if (d > 1e-4f) {
                moved++;
                if (layer.boneWeights != null && layer.boneWeights[b] == 0f) legsMoved++;
            }
        }
        assertTrue(moved > 5, "aiming up must move the upper body; only " + moved + " bones moved");
        assertEquals(0, legsMoved, "the layer's zero-weight bones (hips/knees/feet/toes) must not move");
    }

    @Test
    @DisplayName("a voice gesture keeps every bone inside the body")
    void voiceGestureDoesNotExplodeTheArm() {
        byte[] bytes = read("soldier_animations.mdl");
        MdlFile anims = new MdlFile(bytes);
        SequenceEvaluator eval = new SequenceEvaluator(anims, new AnimSampler(new Reader(bytes), anims));

        MdlFile.SeqDesc stand = anims.findSequence("stand_PRIMARY");
        MdlFile.SeqDesc help = anims.findSequence("gesture_primary_help");
        assertNotNull(stand);
        assertNotNull(help, "the HANDMOUTH gesture must be present");
        assertTrue(help.autoLayers.length > 0, "it carries the left-arm layer that broke");

        // The arm layer is absolute (animflags 0x0000) and belongs on the accumulator, after the
        // gesture delta has been merged in, not on the gesture's own local buffer.
        int handL = -1;
        for (MdlFile.Bone b : anims.bones) {
            if (b.name.equals("bip_hand_L")) handL = b.index;
        }
        assertTrue(handL >= 0, "bip_hand_L not found");

        float[] pos = new float[86 * 3];
        float[] rot = new float[86 * 4];

        for (float cycle = 0f; cycle <= 1f; cycle += 0.1f) {
            eval.evaluate(stand, 0.3f, 0f, 0f, pos, rot);
            eval.accumulateGesture(help, cycle, 1.0f, pos, rot);

            float[] boneToWorld = new float[86 * 12];
            Skeleton.buildMatrices(anims, pos, rot, boneToWorld);

            // Measured: the hand travels x +12..+33 and no bone passes 81 hu from the origin.
            for (MdlFile.Bone b : anims.bones) {
                int o = b.index * 12;
                float x = boneToWorld[o + 3], y = boneToWorld[o + 7], z = boneToWorld[o + 11];
                float d = (float) Math.sqrt(x * x + y * y + z * z);
                assertTrue(d < 100f, String.format(
                        "bone %s reached %.1f hu from the origin at cycle %.1f — the gesture layer "
                                + "is being composed in the wrong space", b.name, d, cycle));
            }

            // HANDMOUTH raises the hand to the mouth, and +X is forward in Source game space.
            float handX = boneToWorld[handL * 12 + 3];
            assertTrue(handX > 0f, String.format(
                    "the left hand is %.1f hu BEHIND the body at cycle %.1f — the absolute arm "
                            + "layer is being blended in delta space", handX, cycle));
        }
    }

    @Test
    @DisplayName("a gesture with no autolayer leaves the legs alone")
    void gestureRespectsItsBoneMask() {
        byte[] bytes = read("soldier_animations.mdl");
        MdlFile anims = new MdlFile(bytes);
        SequenceEvaluator eval = new SequenceEvaluator(anims, new AnimSampler(new Reader(bytes), anims));

        MdlFile.SeqDesc stand = anims.findSequence("stand_PRIMARY");
        MdlFile.SeqDesc go = anims.findSequence("gesture_primary_go");
        assertNotNull(go, "the FINGERPOINT gesture must be present");
        assertNotNull(go.boneWeights, "it must carry a weight list");

        float[] base = new float[86 * 3];
        float[] baseRot = new float[86 * 4];
        eval.evaluate(stand, 0.3f, 0f, 0f, base, baseRot);

        float[] pos = base.clone();
        float[] rot = baseRot.clone();
        eval.accumulateGesture(go, 0.5f, 1.0f, pos, rot);

        int moved = 0, zeroWeightMoved = 0;
        for (int b = 0; b < anims.bones.length; b++) {
            float d = 0f;
            for (int k = 0; k < 4; k++) d += Math.abs(baseRot[b * 4 + k] - rot[b * 4 + k]);
            if (d > 1e-4f) {
                moved++;
                if (go.boneWeights[b] == 0f) zeroWeightMoved++;
            }
        }
        assertTrue(moved > 3, "the gesture must actually move the upper body, moved " + moved);
        assertEquals(0, zeroWeightMoved, "the zero-weight leg bones must not move");
    }

    @Test
    @DisplayName("float16 decodes Valve's variant, where exponent 31 is maxfloat not infinity")
    void valveFloat16() {
        assertEquals(0f, AnimDecode.float16(0x0000), 1e-9f);
        assertEquals(1f, AnimDecode.float16(0x3C00), 1e-6f);
        assertEquals(-2f, AnimDecode.float16(0xC000), 1e-6f);
        // IEEE would give infinity here; Valve clamps to maxfloat.
        assertEquals(65504.0f, AnimDecode.float16(0x7C00), 1f);
    }
}
