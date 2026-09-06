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

/**
 * Emits the Soldier mesh, skinned by the real TF2 animation data. File space F, as authored: +X is
 * the model's left, +Y up, +Z forward, and raw VVD positions live here. Source game space S: +X
 * forward, +Y left, +Z up, holding every animated bone transform and so every skinned vertex.
 * Minecraft M: +X east, +Y up, +Z south. Skinned vertices arrive in S, because the animation's own
 * root track carries the authoring rotation, so only {@link #SOURCE_TO_MC} is applied.
 */
public final class SoldierBody {

    /** Hammer units to blocks. */
    private static final float K = 1.0f / Units.S;

    private static final long START_NANOS = System.nanoTime();

    /** One locomotion state machine for the local player. Multi-player gets a per-entity map later. */
    private static final com.soldiermc.animstate.TfAnimState ANIM =
            new com.soldiermc.animstate.TfAnimState();

    private static long lastPoseNanos = System.nanoTime();

    /**
     * {@code GetCurrentMaxGroundSpeed()} at the edge of the locomotion blend box, hu/s. Hardcoded
     * rather than read from {@code mstudiomovement_t}.
     */
    private static final float RUN_BOX_EDGE_SPEED = 240.0f;
    private static final float CROUCH_BOX_EDGE_SPEED = 80.0f;

    /** Exposed for the instrument panel. */
    private static volatile String lastActivity = "-";
    private static volatile float lastCycle;
    private static volatile float lastRate = 1f;

    /** The locomotion and gesture state machine. */
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

    /** The aim chain, exposed for the instrument panel. */
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

    /**
     * Source game space to Minecraft: S.x → M.x, S.y → M.−z, S.z → M.y. Orthonormal, determinant +1.
     * JOML {@code Matrix4f} is column-major, so each column is the image of one S axis.
     */
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

    /**
     * @return true if the Soldier was drawn and vanilla rendering should be cancelled
     */
    public static boolean submit(AvatarRenderState state, PoseStack pose,
                                 SubmitNodeCollector collector) {
        SoldierAssets.ensureLoaded();
        if (!SoldierAssets.ready()) return false;

        StudioModel model = SoldierAssets.model();
        final float[] palette = poseFor(state);

        pose.pushPose();

        // 1. Yaw about MC +Y, in Source degrees. At the head of submit the PoseStack is
        //    translation-only and world-aligned — setupRotations, scale(-1,-1,1) and
        //    translate(0,-1.501,0) have not run yet.
        // The converged feet yaw, not the view yaw; the aim layer swings the upper body to the view.
        pose.mulPose(Axis.YP.rotationDegrees(ANIM.currentFeetYaw()));

        // 2. Hammer units to blocks, kept as its own step: PoseStack$Pose.scale returns early for a
        //    uniform non-negative scale and never touches the normal matrix, so normals stay unit.
        pose.scale(K, K, K);

        // 3. Source -> Minecraft.
        pose.mulPose(SOURCE_TO_MC);

        for (Map.Entry<Integer, int[]> e : model.trianglesByMaterial.entrySet()) {
            Identifier tex = textureFor(e.getKey());
            if (tex == null) continue;   // eyes: placeholder textures
            final int[] indices = e.getValue();
            RenderType type = RenderTypes.entitySolid(tex);
            collector.submitCustomGeometry(pose, type,
                    (p, vc) -> emit(p, vc, model, indices, palette, state.lightCoords));
        }

        pose.popPose();
        return true;
    }

    /** Material 0 is the body, hat and webbing; material 2 is the head. 1 and 3 are the eyeballs. */
    private static Identifier textureFor(int material) {
        return switch (material) {
            case 0 -> SoldierAssets.bodyTexture();
            case 2 -> SoldierAssets.headTexture();
            default -> null;
        };
    }

    /**
     * Advance the animation state machine, exactly once per rendered frame, from the
     * {@code Minecraft#renderFrame} mixin. {@code CMultiPlayerAnimState::Update} takes no {@code dt}
     * and is written for one call per frame, as {@code UpdateClientSideAnimations} gives it;
     * {@code updateFeetYaw}'s 45 degree goal step is per-call, so a second call in a frame doubles it.
     */
    public static void advance() {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        if (player == null) return;

        long nowNanos = System.nanoTime();
        float now = (nowNanos - START_NANOS) / 1_000_000_000.0f;
        // 0.25 is the port's own clamp; TF2 clamps nothing on this path. GetAnimTimeInterval's 0.2
        // belongs to StudioFrameAdvance, which returns immediately for a client-side-animating entity.
        float dt = Math.min((nowNanos - lastPoseNanos) / 1_000_000_000.0f, 0.25f);
        lastPoseNanos = nowNanos;

        // Gestures advance whether or not the ported physics are driving; one started with the
        // physics off would otherwise never retire.
        if (!SoldierMovement.isActive()) {
            ANIM.tickGestures(dt);
            return;
        }

        // Speed from the ported movement engine, not Minecraft's walkAnimationSpeed.
        float velX = SoldierMovement.velX();
        float velY = SoldierMovement.velY();
        float speed2D = SoldierMovement.horizontalSpeed();

        // The live view yaw, not the render state's — c_tf_player.cpp:4278 splits the same way,
        // EyeAngles() for the local player and the interpolated networked angle for everyone else.
        // yHeadRot is written only by Player.aiStep, once per 20 Hz tick; getYRot() is written by
        // Entity.turn every frame. Pitch is not stale: Entity.turn writes xRot and xRotO with the
        // same delta, so the interpolated value already equals the live one.
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
        // paramIndex0 is move_y and paramIndex1 is move_x, so these arguments are not swapped.
        SoldierAssets.evaluator().evaluate(seq, ANIM.cycle(),
                ANIM.poseMoveY(), ANIM.poseMoveX(), pos, rot);

        // Cross-fade from the outgoing sequence.
        float w = ANIM.blendWeight();
        if (w < 1f) {
            var prevSeq = SoldierAssets.sequenceFor(ANIM.prevActivity());
            float[] pPos = new float[86 * 3];
            float[] pRot = new float[86 * 4];
            SoldierAssets.evaluator().evaluate(prevSeq, ANIM.prevCycle(),
                    ANIM.poseMoveY(), ANIM.poseMoveX(), pPos, pRot);
            // Smoothstep rather than linear.
            float t = w * w * (3f - 2f * w);
            com.soldiermc.studio.SequenceEvaluator.blendSkeleton(
                    anims.bones.length, pPos, pRot, pos, rot, t);
            System.arraycopy(pPos, 0, pos, 0, pos.length);
            System.arraycopy(pRot, 0, rot, 0, rot.length);
        }

        // The aim matrix, as an additive delta. Its weight list zeroes the eight leg bones, so the
        // upper body tracks the view while the legs keep walking.
        SoldierAssets.evaluator().applyAutoLayers(seq,
                ANIM.poseBodyYaw(), ANIM.poseBodyPitch(), pos, rot);

        // Gesture layers go last, after the aim matrix: post-multiplication does not commute.
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

        // Scratch allocated once per emit, not once per vertex; the mesh is ~8,200 drawn triangles.
        // Locals rather than statics, so two entities' emits cannot share them.
        final float[] sp = new float[3];
        final float[] sn = new float[3];
        final org.joml.Vector3f wp = new org.joml.Vector3f();
        final org.joml.Vector3f wn = new org.joml.Vector3f();

        for (int i = 0; i + 2 < indices.length; i += 3) {
            // Winding reversed: v0, v2, v1. Source's front faces wind the opposite way to
            // Minecraft's backface culling, so file order culls every near surface.
            VvdFile.Vertex last = verts.get(indices[i + 1]);
            vertex(p, vc, verts.get(indices[i]), palette, light, sp, sn, wp, wn);
            vertex(p, vc, verts.get(indices[i + 2]), palette, light, sp, sn, wp, wn);
            vertex(p, vc, last, palette, light, sp, sn, wp, wn);
            // submitCustomGeometry's buffer is QUADS-topology; a degenerate fourth vertex makes each
            // triangle a quad. The same vertex as the last, so push it again rather than re-skin it.
            push(vc, last, wp, wn, light);
        }
    }

    private static void vertex(PoseStack.Pose p, VertexConsumer vc, VvdFile.Vertex v,
                               float[] palette, int light,
                               float[] sp, float[] sn,
                               org.joml.Vector3f wp, org.joml.Vector3f wn) {
        Skeleton.skinPosition(palette, v, sp);
        Skeleton.skinNormal(palette, v, sn);
        // Pose exposes transformNormal but not transformPosition, so the position goes through the
        // matrix directly; transformNormal uses the separate normal matrix.
        p.pose().transformPosition(sp[0], sp[1], sp[2], wp);
        p.transformNormal(sn[0], sn[1], sn[2], wn);
        push(vc, v, wp, wn, light);
    }

    /** Push an already-transformed vertex. Separate so the degenerate fourth can reuse the work. */
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
