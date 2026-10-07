package com.soldiermc.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementFidelityTest {

    private static final float DT = SourceFeel.DT;

    @Test
    @DisplayName("holding W settles at the class max, 240 hu/s")
    void groundTopSpeed() {
        Sim s = new Sim();
        for (int i = 0; i < 400; i++) {
            s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
            s.step();
        }
        assertNear("ground top speed", s.mv.speed2D(), 240.0f, 0.5f);
    }

    @Test
    @DisplayName("a plain jump launches at 271 hu/s: three half-steps of gravity, not two")
    void jumpLaunchVelocity() {
        Sim s = new Sim();
        for (int i = 0; i < 20; i++) s.step();
        s.mv.buttons = Buttons.IN_JUMP;
        s.step();
        assertNear("jump launch velZ", s.mv.velZ, 271.0f, 1.0f);
    }

    @Test
    @DisplayName("a plain jump apexes at 50.04 hu, matching Source's own discrete integration")
    void jumpApex() {
        Sim s = new Sim();
        for (int i = 0; i < 20; i++) s.step();
        s.mv.buttons = Buttons.IN_JUMP;
        s.step();
        s.mv.buttons = 0;

        float apex = 0f;
        for (int i = 0; i < 200; i++) {
            s.step();
            apex = Math.max(apex, s.mv.originZ);
            if (s.move.onGround() && i > 2) break;
        }
        // Not the closed form 289^2/1600 = 52.2; the engine integrates discretely.
        assertNear("jump apex", apex, 50.04f, 1.5f);
    }

    @Test
    @DisplayName("air strafing accelerates past the 240 ground cap")
    void airStrafeGainsSpeed() {
        float best = Math.max(strafeRun(-SourceFeel.CL_SIDESPEED, +1.6f),
                              strafeRun(+SourceFeel.CL_SIDESPEED, -1.6f));
        assertTrue(best > 240.0f,
                "air strafe must exceed the 240 ground cap, got " + best);
    }

    @Test
    @DisplayName("mismatched strafe and turn does NOT gain, so the basis is not mirrored")
    void mismatchedStrafeDoesNotGain() {
        float matched = strafeRun(-SourceFeel.CL_SIDESPEED, +1.6f);
        float mismatched = strafeRun(-SourceFeel.CL_SIDESPEED, -1.6f);
        // With a mirrored basis both pairings would gain equally.
        assertTrue(mismatched < matched - 10.0f,
                "mismatched pairing gained too much: matched " + matched + " vs " + mismatched);
    }

    @Test
    @DisplayName("PreventBunnyJumping clamps to 288 hu/s on every ground jump")
    void bunnyhopClamp() {
        Sim s = new Sim();
        for (int i = 0; i < 20; i++) s.step();
        s.mv.velX = 900.0f;
        s.mv.buttons = Buttons.IN_JUMP;
        s.step();
        // 1.2 x 240.
        assertNear("bunnyhop clamp", s.mv.speed2D(), 288.0f, 1.0f);
    }

    @Test
    @DisplayName("sliding along a wall does not lose distance to double-counting")
    void wallSlideCanary() {
        Sim s = new Sim();
        s.world.wallX = 0f;
        s.mv.originX = -10f;
        for (int i = 0; i < 20; i++) s.step();

        float startY = s.mv.originY;
        for (int i = 0; i < 200; i++) {
            s.mv.viewYaw = 85f;
            s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
            s.step();
        }
        float travelled = Math.abs(s.mv.originY - startY);
        float ideal = 240.0f * 200 * DT;
        assertTrue(travelled > ideal * 0.85f,
                "wall slide lost distance: " + travelled + " of " + ideal);
    }

    @Test
    @DisplayName("surfaceFriction is 0.25 only while rising, 1.0 descending")
    void surfaceFrictionWindow() {
        Sim s = new Sim();
        for (int i = 0; i < 20; i++) s.step();
        s.mv.buttons = Buttons.IN_JUMP;
        s.step();
        s.mv.buttons = 0;

        boolean sawQuarterRising = false;
        boolean sawOneDescending = false;
        for (int i = 0; i < 100 && !s.move.onGround(); i++) {
            s.step();
            if (s.mv.velZ > 0 && s.move.surfaceFriction() < 0.5f) sawQuarterRising = true;
            if (s.mv.velZ < 0 && s.move.surfaceFriction() > 0.9f) sawOneDescending = true;
        }
        assertTrue(sawQuarterRising, "never saw surfaceFriction 0.25 while rising");
        assertTrue(sawOneDescending, "never saw surfaceFriction 1.0 while descending");
    }

    @Test
    @DisplayName("walking head-on into a wall does not build momentum, and does not launch on release")
    void wallDoesNotBuildMomentum() {
        Sim s = new Sim();
        s.world.wallX = 0f;
        s.mv.originX = -50f;
        s.mv.viewYaw = 0f;
        for (int i = 0; i < 20; i++) s.step();

        float peakWhilePushing = 0f;
        for (int i = 0; i < 200; i++) {
            s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
            s.step();
            peakWhilePushing = Math.max(peakWhilePushing, s.mv.speed());
        }

        assertTrue(peakWhilePushing <= 245.0f,
                "speed grew while pushing into a wall: " + peakWhilePushing
                        + " hu/s (must stay at or under the 240 cap)");

        s.mv.viewYaw = 180f;
        float peakAfter = 0f;
        for (int i = 0; i < 20; i++) {
            s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
            s.step();
            peakAfter = Math.max(peakAfter, s.mv.speed());
        }
        assertTrue(peakAfter <= 245.0f,
                "released stored momentum after leaving the wall: " + peakAfter + " hu/s");
    }

    @Test
    @DisplayName("backpedalling caps at 216 hu/s (tf_clamp_back_speed), not 240")
    void backpedalIsClamped() {
        Sim s = new Sim();
        for (int i = 0; i < 400; i++) {
            s.mv.forwardMove = -SourceFeel.CL_BACKSPEED;
            s.step();
        }
        // 0.9 x 240.
        assertNear("backpedal speed", s.mv.speed2D(), 216.0f, 1.0f);
    }

    @Test
    @DisplayName("S+D is NOT clamped: the back component is under the cap")
    void diagonalBackIsNotClamped() {
        Sim s = new Sim();
        for (int i = 0; i < 400; i++) {
            s.mv.forwardMove = -SourceFeel.CL_BACKSPEED;
            s.mv.sideMove = SourceFeel.CL_SIDESPEED;
            s.step();
        }
        // Back component is 240 * cos(45) = 169.7, under the 216 cap.
        assertNear("S+D speed", s.mv.speed2D(), 240.0f, 1.0f);
    }

    @Test
    @DisplayName("a crouch tap does not permanently latch the duck state")
    void crouchTapDoesNotLatch() {
        Sim s = new Sim();
        for (int i = 0; i < 20; i++) s.step();

        // Well under TIME_TO_DUCK (200 ms, about 13 substeps).
        for (int i = 0; i < 3; i++) { s.mv.buttons = Buttons.IN_DUCK; s.step(); }
        s.mv.buttons = 0;
        for (int i = 0; i < 40; i++) s.step();

        // A latched duck state sends checkJumpButton down the assign branch (277).
        s.mv.buttons = Buttons.IN_JUMP;
        s.step();
        assertNear("launch after crouch tap", s.mv.velZ, 271.0f, 1.0f);
    }

    @Test
    @DisplayName("holding crouch refuses the jump entirely (FL_DUCKING gate)")
    void duckedCannotJump() {
        Sim s = new Sim();
        for (int i = 0; i < 20; i++) s.step();

        for (int i = 0; i < 30; i++) { s.mv.buttons = Buttons.IN_DUCK; s.step(); }

        float before = s.mv.velZ;
        s.mv.buttons = Buttons.IN_DUCK | Buttons.IN_JUMP;
        s.step();
        assertTrue(s.mv.velZ <= before + 1.0f,
                "ducked player jumped: velZ went " + before + " -> " + s.mv.velZ);
    }

    private static float strafeRun(float sideMove, float yawRate) {
        Sim s = new Sim();
        for (int i = 0; i < 300; i++) {
            s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
            s.step();
        }
        s.mv.buttons = Buttons.IN_JUMP;
        s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
        s.step();
        s.mv.buttons = 0;

        float peak = s.mv.speed2D();
        for (int i = 0; i < 60 && !s.move.onGround(); i++) {
            s.mv.sideMove = sideMove;
            s.mv.viewYaw += yawRate;
            s.step();
            peak = Math.max(peak, s.mv.speed2D());
        }
        return peak;
    }

    @Test
    @DisplayName("applyAbsVelocityImpulse leaves the ground flag alone, as ApplyAbsVelocityImpulse does")
    void selfBlastImpulseDoesNotClearTheGroundFlag() {
        Sim s = new Sim();
        s.step();
        assertTrue(s.move.onGround(), "precondition: standing on the floor");

        // Below GROUND_DETACH_SPEED (250) the player stays grounded and velZ is zeroed.
        s.move.applyAbsVelocityImpulse(s.mv, -364.3f, 0f, 91.1f);
        assertTrue(s.move.onGround(), "the impulse itself must not clear the ground flag");
        assertNear("velZ right after the impulse", s.mv.velZ, 91.1f, 0.01f);

        s.step();
        assertNear("velZ after one substep", s.mv.velZ, 0f, 0.01f);
        assertTrue(Math.abs(s.mv.velX) > 100f, "the horizontal shove survives, got " + s.mv.velX);
    }

    @Test
    @DisplayName("a strong self-blast detaches through CategorizePosition, not through the impulse")
    void strongSelfBlastDetachesViaSpeedThreshold() {
        Sim s = new Sim();
        s.step();

        s.move.applyAbsVelocityImpulse(s.mv, 0f, 0f, 415.466f);
        assertTrue(s.move.onGround(), "still grounded until movement runs");
        s.step();
        assertTrue(!s.move.onGround(), "velZ > 250 must detach via CategorizePosition");
        assertTrue(s.mv.velZ > 380f, "the lift must survive, got " + s.mv.velZ);
    }

    @Test
    @DisplayName("airblast pushback DOES clear the flag, and floors velZ at JUMP_MIN_SPEED first")
    void pushbackImpulseFloorsAndDetaches() {
        Sim s = new Sim();
        s.step();
        assertTrue(s.move.onGround());

        s.move.applyGenericPushbackImpulse(s.mv, 200f, 0f, 10f);
        assertTrue(!s.move.onGround(), "pushback clears the ground flag itself");
        assertNear("velZ floored", s.mv.velZ, SourceFeel.JUMP_MIN_SPEED, 0.001f);
    }

    @Test
    @DisplayName("an absurd impulse is rescaled as a whole vector, and a insane one discarded")
    void impulseSanityBound() {
        Sim s = new Sim();
        s.step();

        s.mv.velX = 0f; s.mv.velY = 0f; s.mv.velZ = 0f;
        s.move.applyAbsVelocityImpulse(s.mv, 5000f, 0f, 0f);
        assertNear("rescaled to MAX_ENTITY_SPEED", s.mv.velX, SourceFeel.MAX_ENTITY_SPEED, 0.01f);

        s.mv.velX = 0f;
        s.move.applyAbsVelocityImpulse(s.mv, 1e9f, 0f, 0f);
        assertNear("beyond 100x is discarded outright", s.mv.velX, 0f, 0.001f);
    }

    private static void assertNear(String what, float actual, float expected, float tol) {
        assertTrue(Math.abs(actual - expected) <= tol,
                what + ": expected " + expected + " +/- " + tol + ", got " + actual);
    }

    private static final class Sim {
        final FlatWorld world = new FlatWorld();
        final SourceMovement move = new SourceMovement(world);
        final MoveData mv = new MoveData();

        Sim() {
            mv.clientMaxSpeed = SourceFeel.CLASS_MAX_SPEED;
            mv.maxSpeed = SourceFeel.TF_MAX_SPEED;
            move.reset(true);
        }

        void step() {
            move.processMovement(mv);
            mv.forwardMove = 0f;
            mv.sideMove = 0f;
        }
    }
}
