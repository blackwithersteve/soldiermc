package com.soldiermc.source;

/** Values not transcribed from Valve's source. */
public final class Unverified {

    /** Exact != on the blocked axis test; Shapes.collide's back-off is unchecked. */
    public static final boolean EXACT_BLOCKED_TEST = true;

    /** Relative epsilon used when EXACT_BLOCKED_TEST is false. */
    public static final double BLOCKED_TEST_EPSILON = 1.0e-7;

    /** Not in Source: opposite plane normals occur in Minecraft but not in a BSP. */
    public static final float CREASE_DEGENERATE_EPSILON = 1.0e-12f;

    private Unverified() {
    }
}
