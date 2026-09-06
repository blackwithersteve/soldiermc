package com.soldiermc.bridge;

import com.soldiermc.source.SourceFeel;
import com.soldiermc.source.TraceResult;
import com.soldiermc.source.Unverified;

/**
 * Turns Minecraft's per-axis clamped displacement into Source's single-fraction trace result.
 * {@code Entity.collideBoundingBox} resolves each axis independently and completes the unblocked
 * ones fully, where {@code TryPlayerMove} wants one fraction along the ray plus the impact plane.
 */
public final class SweepResolve {

    /**
     * @param out       filled with fraction, endpoint and plane normal, in ENGINE space
     * @param want      requested displacement in Minecraft space
     * @param got       what Minecraft allowed, per axis
     * @param from,to   the engine-space endpoints, for interpolating the result
     * @return true if anything was blocked
     */
    public static boolean resolve(TraceResult out,
                                  double wantX, double wantY, double wantZ,
                                  double gotX, double gotY, double gotZ,
                                  float fromX, float fromY, float fromZ,
                                  float toX, float toY, float toZ) {

        boolean bx = blocked(gotX, wantX);
        boolean by = blocked(gotY, wantY);
        boolean bz = blocked(gotZ, wantZ);

        if (!bx && !by && !bz) return false;

        float best = 1.0f;
        int axis = -1;
        if (bx) { float f = ratio(gotX, wantX); if (f < best) { best = f; axis = 0; } }
        if (by) { float f = ratio(gotY, wantY); if (f < best) { best = f; axis = 1; } }
        if (bz) { float f = ratio(gotZ, wantZ); if (f < best) { best = f; axis = 2; } }

        if (axis < 0) return false;

        // DIST_EPSILON back-off (public/coordsize.h:35). Source's trace stops the hull short of the
        // plane; interpolating the endpoint in float lands it a sub-ULP past instead, and
        // Minecraft's `box.maxX <= shape.minX` guard then drops that block from collision for good.
        float axisLenHu = switch (axis) {
            case 0 -> Math.abs(toX - fromX);
            case 1 -> Math.abs(toZ - fromZ);
            default -> Math.abs(toY - fromY);
        };
        if (axisLenHu > 0f) {
            best -= SourceFeel.DIST_EPSILON / axisLenHu;
            if (best < 0f) best = 0f;
        }

        out.fraction = best;
        out.endX = fromX + (toX - fromX) * best;
        out.endY = fromY + (toY - fromY) * best;
        out.endZ = fromZ + (toZ - fromZ) * best;

        switch (axis) {
            case 0 -> out.normalX = wantX > 0 ? -1f : 1f;   // MC x -> engine x
            case 1 -> out.normalZ = wantY > 0 ? -1f : 1f;   // MC y -> engine z (up)
            // MC z -> engine y is NEGATED by the rotation, so the normal's sign flips with it.
            default -> out.normalY = wantZ > 0 ? 1f : -1f;
        }
        return true;
    }

    /**
     * Did this axis get clipped? Exact {@code !=} in double, matching {@code Entity.collide}; the
     * epsilon fallback exists because the back-off inside {@code Shapes.collide} has not been read
     * out of the bytecode yet — see {@link Unverified#EXACT_BLOCKED_TEST}.
     */
    public static boolean blocked(double got, double want) {
        if (want == 0.0) return false;
        if (Unverified.EXACT_BLOCKED_TEST) return got != want;
        return Math.abs(got - want) > Math.abs(want) * Unverified.BLOCKED_TEST_EPSILON;
    }

    private static float ratio(double got, double want) {
        if (want == 0.0) return 1.0f;
        float f = (float) (got / want);
        if (f < 0f) return 0f;
        return Math.min(f, 1.0f);
    }

    private SweepResolve() {
    }
}
