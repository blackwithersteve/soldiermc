package com.soldiermc.bridge;

import com.soldiermc.source.TraceResult;
import com.soldiermc.source.WorldQuery;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/** Never calls Entity.move, whose side effects would then fire once per substep. */
public final class McWorldQuery implements WorldQuery {

    /** Minecraft's player width in blocks; TF2's 49 hu hull cannot fit a 1-block gap. */
    private static final double PROBE_WIDTH = 0.6;

    private Entity entity;
    private Level level;

    public void bind(Entity entity, Level level) {
        this.entity = entity;
        this.level = level;
    }

    @Override
    public void trace(TraceResult out, float hullHeight,
                      float fromX, float fromY, float fromZ,
                      float toX, float toY, float toZ) {
        out.clear(toX, toY, toZ);
        if (entity == null || level == null) return;

        double fx = Units.blocks(fromX), fy = Units.blocks(fromZ), fz = Units.engineYToMcZ(fromY);
        double tx = Units.blocks(toX), ty = Units.blocks(toZ), tz = Units.engineYToMcZ(toY);

        double wantX = tx - fx, wantY = ty - fy, wantZ = tz - fz;
        if (wantX == 0.0 && wantY == 0.0 && wantZ == 0.0) return;

        AABB box = hullAt(fx, fy, fz, hullHeight);

        // Entities only: collideBoundingBox gathers block shapes itself.
        List<VoxelShape> entityShapes =
                level.getEntityCollisions(entity, box.expandTowards(wantX, wantY, wantZ));

        Vec3 want = new Vec3(wantX, wantY, wantZ);
        Vec3 got = Entity.collideBoundingBox(entity, want, box, level, entityShapes);

        SweepResolve.resolve(out, wantX, wantY, wantZ, got.x, got.y, got.z,
                fromX, fromY, fromZ, toX, toY, toZ);

        // Unconditional: walkMove returns early on fraction 1, so a gated check never runs.
        out.startSolid = !level.noCollision(entity, box);
        out.allSolid = out.startSolid && out.fraction <= 0f;
    }

    @Override
    public boolean solidAt(float hullHeight, float x, float y, float z) {
        if (entity == null || level == null) return false;
        AABB box = hullAt(Units.blocks(x), Units.blocks(z), Units.engineYToMcZ(y), hullHeight);
        return !level.noCollision(entity, box);
    }

    private AABB hullAt(double mcX, double mcY, double mcZ, float hullHeight) {
        double h = Units.blocks(hullHeight);
        double half = PROBE_WIDTH / 2.0;
        return new AABB(mcX - half, mcY, mcZ - half, mcX + half, mcY + h, mcZ + half);
    }

    public boolean outsideLoadedWorld(float huX, float huY, float huZ) {
        if (level == null) return true;
        double mcX = Units.blocks(huX);
        double mcY = Units.blocks(huZ);
        double mcZ = Units.engineYToMcZ(huY);
        if (mcY < level.getMinY() || mcY > level.getMaxY()) return true;
        return !level.hasChunkAt(net.minecraft.util.Mth.floor(mcX), net.minecraft.util.Mth.floor(mcZ));
    }

    @Override
    public void traceLineBrush(TraceResult out,
                               float fromX, float fromY, float fromZ,
                               float toX, float toY, float toZ) {
        lineTrace(out, -1, false, fromX, fromY, fromZ, toX, toY, toZ);
    }

    @Override
    public void traceLineSolid(TraceResult out, int ignoreEntityId,
                               float fromX, float fromY, float fromZ,
                               float toX, float toY, float toZ) {
        lineTrace(out, ignoreEntityId, true, fromX, fromY, fromZ, toX, toY, toZ);
    }

    private void lineTrace(TraceResult out, int ignoreEntityId, boolean includeEntities,
                           float fromX, float fromY, float fromZ,
                           float toX, float toY, float toZ) {
        out.clear(toX, toY, toZ);
        if (entity == null || level == null) return;

        Vec3 from = new Vec3(Units.blocks(fromX), Units.blocks(fromZ), Units.engineYToMcZ(fromY));
        Vec3 to = new Vec3(Units.blocks(toX), Units.blocks(toZ), Units.engineYToMcZ(toY));
        double total = from.distanceTo(to);
        if (total < 1.0e-9) return;

        // COLLIDER with Fluid.NONE is Minecraft's closest match to MASK_SOLID_BRUSHONLY.
        var hit = level.clip(new net.minecraft.world.level.ClipContext(
                from, to,
                net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE,
                entity));

        Vec3 end = to;
        if (hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS) {
            end = hit.getLocation();
            out.fraction = (float) (from.distanceTo(end) / total);
            var dir = hit.getDirection();
            out.normalX = dir.getStepX();
            out.normalY = -dir.getStepZ();
            out.normalZ = dir.getStepY();
            out.endX = Units.hu(end.x);
            out.endY = Units.mcZToEngineY(end.z);
            out.endZ = Units.hu(end.y);
        }

        if (!includeEntities) return;

        // Searching only up to the block hit lets a wall win without comparing distances.
        AABB sweep = new AABB(from, end).inflate(1.0);
        var entHit = net.minecraft.world.entity.projectile.ProjectileUtil.getEntityHitResult(
                level, entity, from, end, sweep,
                e -> !e.isSpectator() && e.isPickable() && e.getId() != ignoreEntityId, 0f);
        if (entHit != null) {
            Vec3 p = entHit.getLocation();
            out.fraction = (float) (from.distanceTo(p) / total);
            out.endX = Units.hu(p.x);
            out.endY = Units.mcZToEngineY(p.z);
            out.endZ = Units.hu(p.y);
            out.hitEntityId = entHit.getEntity().getId();
            out.normalX = 0f;
            out.normalY = 0f;
            out.normalZ = 0f;
        }
    }
}
