package com.soldiermc.source;

/** The only class in {@code com.soldiermc.source} holding a number not transcribed from Valve's source. */
public final class Unverified {

    /**
     * Whether the viewmodel is right-handed, which sets the sign of the 12 hu lateral muzzle offset
     * (tf_weaponbase.cpp:5750-5753). Unverified: {@code cl_flipviewmodels} is registered client-side,
     * outside the released tree.
     */
    public static final boolean RIGHT_HANDED_VIEWMODEL = true;


    /**
     * Whether the blocked-axis test compares Minecraft's returned displacement to the requested one
     * with exact {@code !=} in double rather than an epsilon. Unverified: the back-off inside
     * {@code Shapes.collide(Axis, AABB, Iterable, double)} has not been read out of the bytecode.
     */
    public static final boolean EXACT_BLOCKED_TEST = true;

    /** Relative epsilon for the fallback path; not a Source value. @see #EXACT_BLOCKED_TEST */
    public static final double BLOCKED_TEST_EPSILON = 1.0e-7;

    /**
     * Guard against a zero-length cross product in the two-plane crease branch. Not present in Source:
     * two exactly opposite plane normals are authorable in Minecraft but not by a BSP brush.
     */
    public static final float CREASE_DEGENERATE_EPSILON = 1.0e-12f;

    private Unverified() {
    }
}
