package com.soldiermc.source;

import com.soldiermc.bridge.SweepResolve;
import com.soldiermc.bridge.Units;

/**
 * Minecraft's collision semantics, not a swept trace: {@code Entity.collideBoundingBox} resolves
 * each axis independently, completing the unblocked ones fully, through the real
 * {@link SweepResolve}. Solid below mcY = 0, plus a slab at mcX &gt;= wallX.
 */
public final class BlockWorld implements WorldQuery {

    /** Wall plane in Minecraft blocks; everything at or beyond this x is solid. */
    public double wallXBlocks = Double.POSITIVE_INFINITY;

    private static final double PROBE_WIDTH = 0.6;

    @Override
    public void trace(TraceResult out, float hullHeight,
                      float fromX, float fromY, float fromZ,
                      float toX, float toY, float toZ) {
        out.clear(toX, toY, toZ);

        // engine -> minecraft (the rotation: mx = ex, my = ez, mz = -ey)
        double fx = Units.blocks(fromX), fy = Units.blocks(fromZ), fz = Units.engineYToMcZ(fromY);
        double tx = Units.blocks(toX), ty = Units.blocks(toZ), tz = Units.engineYToMcZ(toY);

        double wantX = tx - fx, wantY = ty - fy, wantZ = tz - fz;
        if (wantX == 0 && wantY == 0 && wantZ == 0) return;

        double h = Units.blocks(hullHeight);
        double half = PROBE_WIDTH / 2.0;

        // Minecraft's per-axis resolution: Y first, then X, then Z (Direction.axisStepOrder).
        double gotY = clampY(fy, wantY);
        double gotX = clampX(fx + 0.0, wantX, half);
        double gotZ = wantZ; // no geometry constrains z in this world

        SweepResolve.resolve(out, wantX, wantY, wantZ, gotX, gotY, gotZ,
                fromX, fromY, fromZ, toX, toY, toZ);
    }

    private double clampY(double fromY, double wantY) {
        if (wantY >= 0) return wantY;
        double floor = 0.0;
        if (fromY + wantY < floor) return floor - fromY;
        return wantY;
    }

    private double clampX(double fromX, double wantX, double half) {
        if (wallXBlocks == Double.POSITIVE_INFINITY || wantX <= 0) return wantX;
        double limit = wallXBlocks - half;
        if (fromX + wantX > limit) return Math.max(0.0, limit - fromX);
        return wantX;
    }

    /** Not exercised by the bridge-semantics tests; the flat world owns the line-trace cases. */
    @Override
    public void traceLineBrush(TraceResult out,
                               float fromX, float fromY, float fromZ,
                               float toX, float toY, float toZ) {
        out.clear(toX, toY, toZ);
    }

    @Override
    public void traceLineSolid(TraceResult out, int ignoreEntityId,
                               float fromX, float fromY, float fromZ,
                               float toX, float toY, float toZ) {
        out.clear(toX, toY, toZ);
    }

    @Override
    public boolean solidAt(float hullHeight, float x, float y, float z) {
        double mx = Units.blocks(x), my = Units.blocks(z);
        if (my < 0) return true;
        return wallXBlocks != Double.POSITIVE_INFINITY && mx + PROBE_WIDTH / 2.0 > wallXBlocks;
    }
}
