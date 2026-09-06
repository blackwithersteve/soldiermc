package com.soldiermc.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code AngleVectors} / {@code VectorAngles} against hand-computed Source values. */
class AngleMathTest {

    private static final float EPS = 1e-5f;

    @Test
    @DisplayName("positive pitch points DOWN, and +Y is LEFT")
    void sourceConventions() {
        float[] v = new float[9];

        AngleMath.angleVectors(0f, 0f, 0f, v);
        assertEquals(1f, v[0], EPS, "yaw 0 faces +X");
        assertEquals(0f, v[1], EPS);
        assertEquals(0f, v[2], EPS);
        assertEquals(0f, v[3], EPS);
        assertEquals(-1f, v[4], EPS, "right is -Y, because +Y is LEFT in Source");
        assertEquals(1f, v[8], EPS, "up is +Z");

        AngleMath.angleVectors(90f, 0f, 0f, v);
        assertEquals(-1f, v[2], EPS, "pitch +90 points straight DOWN (forward.z = -sin(pitch))");

        AngleMath.angleVectors(0f, 90f, 0f, v);
        assertEquals(0f, v[0], EPS);
        assertEquals(1f, v[1], EPS, "yaw +90 turns toward +Y, i.e. to the LEFT");
    }

    @Test
    @DisplayName("VectorAngles normalises into [0,360), not [-180,180)")
    void vectorAnglesRange() {
        float[] a = new float[2];
        AngleMath.vectorAngles(0f, -1f, 0f, a);
        assertEquals(270f, a[1], EPS, "a yaw of -90 comes back as 270");
        assertTrue(a[0] >= 0f && a[0] < 360f);

        AngleMath.vectorAngles(1f, 0f, 1f, a);
        assertEquals(315f, a[0], EPS, "an upward pitch of -45 comes back as 315");
    }

    @Test
    @DisplayName("straight down takes the degenerate branch — the rocket-jump case")
    void degenerateVerticalBranch() {
        float[] a = new float[2];

        // x == 0 && y == 0: VectorAngles hardcodes the angles instead of computing them.
        AngleMath.vectorAngles(0f, 0f, -1f, a);
        assertEquals(90f, a[0], EPS, "straight down is pitch 90");
        assertEquals(0f, a[1], EPS, "and yaw is forced to 0, whatever the player was facing");

        AngleMath.vectorAngles(0f, 0f, 1f, a);
        assertEquals(270f, a[0], EPS, "straight up is pitch 270");
        assertEquals(0f, a[1], EPS);
    }

    @Test
    @DisplayName("the VectorAngles -> AngleVectors round trip is stable for a downward shot")
    void roundTrip() {
        float[] a = new float[2];
        float[] v = new float[3];

        AngleMath.vectorAngles(0f, 0f, -1f, a);
        AngleMath.forward(a[0], a[1], v);
        assertEquals(0f, v[0], EPS);
        assertEquals(0f, v[1], EPS);
        assertEquals(-1f, v[2], EPS, "down in, down out");

        // A general direction survives the trip too, despite the [0,360) renormalisation.
        float[] src = {0.3f, -0.5f, 0.81f};
        AngleMath.normalize(src, 0);
        AngleMath.vectorAngles(src[0], src[1], src[2], a);
        AngleMath.forward(a[0], a[1], v);
        assertEquals(src[0], v[0], 1e-4f);
        assertEquals(src[1], v[1], 1e-4f);
        assertEquals(src[2], v[2], 1e-4f);
    }
}
