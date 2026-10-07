package com.soldiermc.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.soldiermc.bridge.Units;
import com.soldiermc.studio.Skeleton;
import com.soldiermc.studio.StudioModel;
import com.soldiermc.studio.VvdFile;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;

import java.util.Map;

/** Skinned vertices arrive in Source space; the root track holds the file rotation. */
public final class SoldierBody {

    private static final float K = 1.0f / Units.S;

    private static final long START_NANOS = System.nanoTime();

    private static final com.soldiermc.animstate.TfAnimState ANIM =
            new com.soldiermc.animstate.TfAnimState();

    private static long lastPoseNanos = System.nanoTime();

    /** GetCurrentMaxGroundSpeed at the blend box edge in hu/s, not read from the MDL. */
    private static final float RUN_BOX_EDGE_SPEED = 240.0f;
    private static final float CROUCH_BOX_EDGE_SPEED = 80.0f;

    private static volatile String lastActivity = "-";
    private static volatile float lastCycle;
    private static volatile float lastRate = 1f;

    public static com.soldiermc.animstate.TfAnimState animState() {
        return ANIM;
    }

    public static String lastActivity() {
        return lastActivity;
    }

    public static float lastCycle() {
        return lastCycle;
    }

    public static float lastRate() {
        return lastRate;
    }

    private static volatile float lastEyeYaw;
    private static volatile float lastFeetYaw;
    private static volatile float lastBodyYaw;
    private static volatile float lastBodyPitch;

    public static float lastEyeYaw() {
        return lastEyeYaw;
    }

    public static float lastFeetYaw() {
        return lastFeetYaw;
    }

    public static float lastBodyYaw() {
        return lastBodyYaw;
    }

    public static float lastBodyPitch() {
        return lastBodyPitch;
    }

    /** Source (+X forward, +Y left, +Z up) to Minecraft; JOML takes columns. */
    private static final Matrix4f SOURCE_TO_MC = new Matrix4f(
            1f, 0f, 0f, 0f,
            0f, 0f, -1f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 0f, 1f);

    static {
        if (det3(SOURCE_TO_MC) <= 0f) {
            throw new IllegalStateException("axis map mirrors the model (determinant <= 0)");
        }
    }

    private SoldierBody() {
    }

    /** Returns true when the Soldier is drawn and vanilla rendering must be cancelled. */
    public static boolean submit(AvatarRenderState state, PoseStack pose,
                                 SubmitNodeCollector collector) {
        SoldierAssets.ensureLoaded();
        if (!SoldierAssets.ready()) return false;

        StudioModel model = SoldierAssets.model();
        final float[] palette = poseFor(state);

        pose.pushPose();

        // The PoseStack is translation-only here; setupRotations and its flips run later.
        pose.mulPose(Axis.YP.rotationDegrees(ANIM.currentFeetYaw()));

        // Uniform scale on its own: Pose.scale then leaves the normal matrix alone.
        pose.scale(K, K, K);

        pose.mulPose(SOURCE_TO_MC);

        for (Map.Entry<Integer, int[]> e : model.trianglesByMaterial.entrySet()) {
            Identifier tex = textureFor(e.getKey());
            if (tex == null) continue;
            final int[] indices = e.getValue();
            RenderType type = RenderTypes.entitySolid(tex);
            collector.submitCustomGeometry(pose, type,
                    (p, vc) -> emit(p, vc, model, indices, palette, state.lightCoords));
        }

        pose.popPose();
        return true;
    }

    /** Material 0 is body, hat and webbing; 2 is the head; 1 and 3 are eyeballs. */
    private static Identifier textureFor(int material) {
        return switch (material) {
            case 0 -> SoldierAssets.bodyTexture();
            case 2 -> SoldierAssets.headTexture();
            default -> null;
        };
    }

    /** Call once per rendered frame: updateFeetYaw's 45 degree goal step is per call. */
    public static void advance() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;

        long nowNanos = System.nanoTime();
        float now = (nowNanos - START_NANOS) / 1_000_000_000.0f;
        // TF2 does not clamp dt on this path; 0.25 is this port's own limit.
        float dt = Math.min((nowNanos - lastPoseNanos) / 1_000_000_000.0f, 0.25f);
        lastPoseNanos = nowNanos;

        if (!SoldierMovement.isActive()) {
            ANIM.tickGestures(dt);
            return;
        }

        float velX = SoldierMovement.velX();
        float velY = SoldierMovement.velY();
        float speed2D = SoldierMovement.horizontalSpeed();

        // getYRot updates every frame; yHeadRot only once per 20 Hz tick in Player.aiStep.
        float eyeYaw = Units.sourceYaw(player.getYRot());
        float moveYaw = speed2D > 0.5f
                ? (float) Math.toDegrees(Math.atan2(velY, velX))
                : eyeYaw;

        if (SoldierMovement.consumeJumpEdge()) ANIM.onJump(now);

        ANIM.update(speed2D, SoldierMovement.verticalSpeed(),
                SoldierMovement.onGround(), SoldierMovement.ducked(),
                eyeYaw, player.getXRot(), moveYaw, now, dt,
                RUN_BOX_EDGE_SPEED, CROUCH_BOX_EDGE_SPEED);

        lastActivity = ANIM.activity().name();
        lastCycle = ANIM.cycle();
        lastRate = ANIM.playbackRate();
        lastEyeYaw = eyeYaw;
        lastFeetYaw = ANIM.currentFeetYaw();
        lastBodyYaw = ANIM.poseBodyYaw();
        lastBodyPitch = ANIM.poseBodyPitch();
    }

    private static float[] poseFor(AvatarRenderState state) {
        var anims = SoldierAssets.animModel();
        var seq = SoldierAssets.sequenceFor(ANIM.activity());

        float[] pos = new float[86 * 3];
        float[] rot = new float[86 * 4];
        // paramIndex0 is move_y and paramIndex1 is move_x, so this order is correct.
        SoldierAssets.evaluator().evaluate(seq, ANIM.cycle(),
                ANIM.poseMoveY(), ANIM.poseMoveX(), pos, rot);

        float w = ANIM.blendWeight();
        if (w < 1f) {
            var prevSeq = SoldierAssets.sequenceFor(ANIM.prevActivity());
            float[] pPos = new float[86 * 3];
            float[] pRot = new float[86 * 4];
            SoldierAssets.evaluator().evaluate(prevSeq, ANIM.prevCycle(),
                    ANIM.poseMoveY(), ANIM.poseMoveX(), pPos, pRot);
            float t = w * w * (3f - 2f * w);
            com.soldiermc.studio.SequenceEvaluator.blendSkeleton(
                    anims.bones.length, pPos, pRot, pos, rot, t);
            System.arraycopy(pPos, 0, pos, 0, pos.length);
            System.arraycopy(pRot, 0, rot, 0, rot.length);
        }

        SoldierAssets.evaluator().applyAutoLayers(seq,
                ANIM.poseBodyYaw(), ANIM.poseBodyPitch(), pos, rot);

        // Gestures go after the aim matrix: post-multiplication does not commute.
        if (ANIM.landActive() && SoldierAssets.jumpLand() != null) {
            SoldierAssets.evaluator().accumulateGesture(
                    SoldierAssets.jumpLand(), ANIM.landCycle(), 1.0f, pos, rot);
        }
        var slot0 = SoldierAssets.gesture(ANIM.slot0Gesture());
        if (slot0 != null) {
            SoldierAssets.evaluator().accumulateGesture(slot0, ANIM.slot0Cycle(), 1.0f, pos, rot);
        }

        float[] boneToWorld = new float[86 * 12];
        float[] palette = new float[86 * 12];
        Skeleton.buildMatrices(anims, pos, rot, boneToWorld);
        // poseToBone comes from the mesh model, not the animation model.
        Skeleton.buildPalette(SoldierAssets.model().mdl, boneToWorld, palette);
        return palette;
    }

    private static void emit(PoseStack.Pose p, VertexConsumer vc, StudioModel model,
                             int[] indices, float[] palette, int light) {
        java.util.List<VvdFile.Vertex> verts = model.vertices;

        final float[] sp = new float[3];
        final float[] sn = new float[3];
        final org.joml.Vector3f wp = new org.joml.Vector3f();
        final org.joml.Vector3f wn = new org.joml.Vector3f();

        for (int i = 0; i + 2 < indices.length; i += 3) {
            // v0, v2, v1: Source winds front faces opposite to Minecraft's culling.
            VvdFile.Vertex last = verts.get(indices[i + 1]);
            vertex(p, vc, verts.get(indices[i]), palette, light, sp, sn, wp, wn);
            vertex(p, vc, verts.get(indices[i + 2]), palette, light, sp, sn, wp, wn);
            vertex(p, vc, last, palette, light, sp, sn, wp, wn);
            // The buffer is QUADS; a repeated fourth vertex turns each triangle into a quad.
            push(vc, last, wp, wn, light);
        }
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer vc, VvdFile.Vertex v,
                               float[] palette, int light,
                               float[] sp, float[] sn,
                               org.joml.Vector3f wp, org.joml.Vector3f wn) {
        Skeleton.skinPosition(palette, v, sp);
        Skeleton.skinNormal(palette, v, sn);
        p.pose().transformPosition(sp[0], sp[1], sp[2], wp);
        p.transformNormal(sn[0], sn[1], sn[2], wn);
        push(vc, v, wp, wn, light);
    }

    private static void push(VertexConsumer vc, VvdFile.Vertex v,
                             org.joml.Vector3f wp, org.joml.Vector3f wn, int light) {
        vc.addVertex(wp.x, wp.y, wp.z,
                0xFFFFFFFF,
                v.u, v.v,
                OverlayTexture.NO_OVERLAY,
                light,
                wn.x, wn.y, wn.z);
    }

    private static float det3(Matrix4f m) {
        return m.m00() * (m.m11() * m.m22() - m.m12() * m.m21())
             - m.m10() * (m.m01() * m.m22() - m.m02() * m.m21())
             + m.m20() * (m.m01() * m.m12() - m.m02() * m.m11());
    }
}
