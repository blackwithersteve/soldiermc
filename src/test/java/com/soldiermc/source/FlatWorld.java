package com.soldiermc.source;

/** A world that is solid below z = 0 and empty above it. */
public final class FlatWorld implements WorldQuery {

    /** Optional vertical wall at x = wallX, for the slide canary. */
    public float wallX = Float.POSITIVE_INFINITY;

    @Override
    public void trace(TraceResult out, float hullHeight,
                      float fromX, float fromY, float fromZ,
                      float toX, float toY, float toZ) {
        out.clear(toX, toY, toZ);

        float bestFraction = 1.0f;
        float nx = 0f, ny = 0f, nz = 0f;

        // --- floor at z = 0 ---
        if (toZ < 0f && fromZ >= 0f) {
            float f = (0f - fromZ) / (toZ - fromZ);
            if (f < bestFraction) {
                bestFraction = f;
                nx = 0f; ny = 0f; nz = 1f;
            }
        } else if (fromZ < 0f) {
            out.startSolid = true;
            out.allSolid = toZ < 0f;
        }

        // --- optional wall, plane x = wallX, normal -X ---
        if (wallX != Float.POSITIVE_INFINITY && toX > wallX && fromX <= wallX) {
            float f = (wallX - fromX) / (toX - fromX);
            if (f < bestFraction) {
                bestFraction = f;
                nx = -1f; ny = 0f; nz = 0f;
            }
        }

        if (bestFraction < 1.0f) {
            out.fraction = bestFraction;
            out.endX = fromX + (toX - fromX) * bestFraction;
            out.endY = fromY + (toY - fromY) * bestFraction;
            out.endZ = fromZ + (toZ - fromZ) * bestFraction;
            out.normalX = nx;
            out.normalY = ny;
            out.normalZ = nz;
        }
    }

    /** A zero-width line trace against the same two planes the hull trace uses. */
    @Override
    public void traceLineBrush(TraceResult out,
                               float fromX, float fromY, float fromZ,
                               float toX, float toY, float toZ) {
        out.clear(toX, toY, toZ);

        float best = 1.0f;
        float nx = 0f, ny = 0f, nz = 0f;

        if (toZ < 0f && fromZ >= 0f) {
            float f = (0f - fromZ) / (toZ - fromZ);
            if (f < best) { best = f; nx = 0f; ny = 0f; nz = 1f; }
        }
        if (wallX != Float.POSITIVE_INFINITY && toX > wallX && fromX <= wallX) {
            float f = (wallX - fromX) / (toX - fromX);
            if (f < best) { best = f; nx = -1f; ny = 0f; nz = 0f; }
        }

        if (best < 1.0f) {
            out.fraction = best;
            out.endX = fromX + (toX - fromX) * best;
            out.endY = fromY + (toY - fromY) * best;
            out.endZ = fromZ + (toZ - fromZ) * best;
            out.normalX = nx;
            out.normalY = ny;
            out.normalZ = nz;
        }
    }

    /** No entities in the flat world, so this is the brush trace. */
    @Override
    public void traceLineSolid(TraceResult out, int ignoreEntityId,
                               float fromX, float fromY, float fromZ,
                               float toX, float toY, float toZ) {
        traceLineBrush(out, fromX, fromY, fromZ, toX, toY, toZ);
    }

    @Override
    public boolean solidAt(float hullHeight, float x, float y, float z) {
        return z < 0f || (wallX != Float.POSITIVE_INFINITY && x > wallX);
    }
}
