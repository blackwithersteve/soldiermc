package com.soldiermc.bridge;

import com.soldiermc.source.SourceFeel;
import com.soldiermc.source.TraceResult;
import com.soldiermc.source.Unverified;

/** Converts Minecraft's per-axis clamped move into Source's single trace fraction. */
public final class SweepResolve {

    /** want and got are in Minecraft space; from, to and out are in engine space. */
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

        // Back off DIST_EPSILON: past the plane, Minecraft drops the block from collision.
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
            case 0 -> out.normalX = wantX > 0 ? -1f : 1f;
            case 1 -> out.normalZ = wantY > 0 ? -1f : 1f;
            // MC z is engine -y, so this normal's sign is flipped.
            default -> out.normalY = wantZ > 0 ? 1f : -1f;
        }
        return true;
    }

    /** Exact != matches Entity.collide; see Unverified for the epsilon fallback. */
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
