package com.soldiermc.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The blast as a pure function, asserted to exact floats derived from the Valve source. */
class SelfBlastTest {

    private static final float BASE = 90.0f;
    private static final float EPS = 1e-4f;

    /**
     * Places the blast at exactly {@code d} hu from the player, straight below the world-space
     * centre, so the falloff distance comes out as {@code d} for both reference points.
     */
    private static SelfBlast at(float d, boolean ducked, boolean onGround) {
        SelfBlast b = new SelfBlast();
        // Blast directly under the feet: distance to the origin is d, to the centre is larger, and
        // ApplyToEntity takes the minimum of the two.
        b.compute(0f, 0f, -d, 0f, 0f, 0f, ducked, onGround, false, false, BASE);
        return b;
    }

    @Test
    @DisplayName("the damage and force table, exact")
    void blastTable() {
        // d = 0
        assertEquals(450.000000f, at(0f, false, true).forceMagnitude(), EPS, "d=0 standing grounded");
        assertEquals(540.000000f, at(0f, false, false).forceMagnitude(), EPS, "d=0 standing airborne");
        assertEquals(670.909091f, at(0f, true, true).forceMagnitude(), EPS, "d=0 ducked grounded");
        assertEquals(805.090909f, at(0f, true, false).forceMagnitude(), EPS, "d=0 ducked airborne");

        // d = 1
        assertEquals(448.140496f, at(1f, false, true).forceMagnitude(), EPS, "d=1 standing grounded");
        assertEquals(537.768595f, at(1f, false, false).forceMagnitude(), EPS, "d=1 standing airborne");
        assertEquals(668.136739f, at(1f, true, true).forceMagnitude(), EPS, "d=1 ducked grounded");
        assertEquals(801.764087f, at(1f, true, false).forceMagnitude(), EPS, "d=1 ducked airborne");

        // at and beyond the radius, where the constant 0.5 falloff floors the damage at 45
        assertEquals(225.000000f, at(121f, false, true).forceMagnitude(), EPS, "edge standing grounded");
        assertEquals(270.000000f, at(121f, false, false).forceMagnitude(), EPS, "edge standing airborne");
        assertEquals(335.454545f, at(121f, true, true).forceMagnitude(), EPS, "edge ducked grounded");
        assertEquals(402.545455f, at(121f, true, false).forceMagnitude(), EPS, "edge ducked airborne");
    }

    @Test
    @DisplayName("the airborne force ratio is 6:5, not 2:1")
    void theSixToFiveRatio() {
        // The 0.60 self-damage scale lands before SetDamageForForceCalc latches the value the push
        // is computed from, so it scales the push as well as the health loss. The force scales
        // alone (5.0 grounded, 10.0 airborne) suggest 2:1.
        float grounded = at(0f, false, true).forceMagnitude();
        float airborne = at(0f, false, false).forceMagnitude();
        assertEquals(6.0f / 5.0f, airborne / grounded, 1e-6f,
                "if this reads 2.0 the 0.60 is being applied after the force is latched");
    }

    @Test
    @DisplayName("damage falls off to exactly half at the radius, and the radius is 121 not 146")
    void falloffFloorAndRadius() {
        assertEquals(90.0f, at(0f, false, true).damage, EPS);
        assertEquals(45.0f, at(121f, false, true).damage, EPS, "constant 0.5 falloff, not dmg/radius");

        // The self radius is TF_ROCKET_RADIUS_FOR_RJS = 110*1.1 = 121, not TF_ROCKET_RADIUS = 146.
        SelfBlast beyond = new SelfBlast();
        assertFalse(beyond.compute(0f, 0f, -130f, 0f, 0f, 0f, false, true, false, false, BASE),
                "130 hu is inside 146 but outside 121 — the self blast must miss");
    }

    @Test
    @DisplayName("health lost is trunc(damage + 0.5), subtracted as an int")
    void integerHealthLoss() {
        // m_iHealth is a CNetworkVar<int>: the float is truncated first, then subtracted.
        assertEquals(90, at(0f, false, true).healthLost);
        assertEquals(54, at(0f, false, false).healthLost, "airborne takes 0.60 of it");
        assertEquals(45, at(121f, false, true).healthLost);
        assertEquals(27, at(121f, false, false).healthLost);
    }

    @Test
    @DisplayName("a blast under the feet pushes up and slightly away, never straight up")
    void directionHasTheVerticalBias() {
        SelfBlast b = new SelfBlast();
        b.compute(0f, 0f, 0f, 0f, 0f, 0f, false, true, false, false, BASE);
        // Blast exactly at the feet: the only thing separating it from the world-space centre is
        // the hull half-height and the 10 hu bias, so the push is purely vertical here.
        assertEquals(0f, b.forceX, EPS);
        assertEquals(0f, b.forceY, EPS);
        assertTrue(b.forceZ > 0f, "a blast at your feet must lift you, got " + b.forceZ);

        // Offset horizontally and the push must lean away from the blast.
        SelfBlast side = new SelfBlast();
        side.compute(20f, 0f, 0f, 0f, 0f, 0f, false, true, false, false, BASE);
        assertTrue(side.forceX < 0f, "pushed away from a blast on the +X side");
        assertTrue(side.forceZ > 0f, "and still lifted");
    }

    @Test
    @DisplayName("a Soldier airborne in water takes FULL damage and the airborne force scale")
    void theWaterGate() {
        // The 0.60 needs !onGround and !inWater, while the force scale looks at onGround alone.
        // This is the only stock path to the 1000 clamp.
        SelfBlast b = new SelfBlast();
        b.compute(0f, 0f, 0f, 0f, 0f, 0f, true, false, true, false, BASE);
        assertEquals(90.0f, b.damage, EPS, "no 0.60 in water");
        assertEquals(SourceFeel.DAMAGE_FORCE_CLAMP, b.forceMagnitude(), EPS,
                "90 x 1.4909 x 10 = 1341.8, clamped to 1000");
    }

    @Test
    @DisplayName("the ducked hull ratio uses the magic 55, not the real 62")
    void theDuckHullHack() {
        float standing = at(0f, false, true).forceMagnitude();
        float ducked = at(0f, true, true).forceMagnitude();
        assertEquals(82.0f / 55.0f, ducked / standing, 1e-6f,
                "ApplyPushFromDamage overrides the duck hull z with 55; using the real 62 gives "
                        + "1.3226 and a visibly weaker crouch jump");
    }

    @Test
    @DisplayName("a direct hit is distance zero regardless of geometry")
    void directHitIsDistanceZero() {
        SelfBlast b = new SelfBlast();
        b.compute(0f, 0f, -60f, 0f, 0f, 0f, false, true, false, true, BASE);
        assertEquals(0f, b.falloffDistance, EPS, "m_hEnemy short-circuits the distance");
        assertEquals(90.0f, b.damage, EPS, "so it takes full damage from 60 hu away");
    }
}
