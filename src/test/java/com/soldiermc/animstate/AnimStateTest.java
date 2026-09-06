package com.soldiermc.animstate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The locomotion state machine, driven headlessly with no Minecraft classes involved. */
class AnimStateTest {

    private static final float DT = 0.015f;          // SourceFeel.DT
    private static final float RUN_BOX_EDGE = 240f;
    private static final float CROUCH_BOX_EDGE = 80f;

    /** Source's ground friction: drop = max(speed, stopspeed) * friction * dt. */
    private static float decelerate(float speed) {
        float next = speed - Math.max(speed, 100f) * 4f * DT;
        return next < 0f ? 0f : next;
    }

    private static void step(TfAnimState anim, float speed, float now) {
        anim.update(speed, 0f, true, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
    }

    @Test
    @DisplayName("playback rate is always 1 — TF2 is a 9-way blend and scales stride, not cadence")
    void playbackRateIsAlwaysOne() {
        TfAnimState anim = new TfAnimState();
        float now = 0f;
        for (float speed = 240f; speed > 0.5f; speed = decelerate(speed)) {
            now += DT;
            step(anim, speed, now);
            assertEquals(1.0f, anim.playbackRate(), 0f,
                    "base_playeranimstate.cpp:545 — with a 9-way blend the rate is always 1");
        }
    }

    @Test
    @DisplayName("the legs keep their cadence all the way through a full-speed stop")
    void decelerationDoesNotStallTheRunCycle() {
        TfAnimState anim = new TfAnimState();
        float now = DT;
        step(anim, 240f, now);
        assertEquals(TfAnimState.Activity.RUN, anim.activity(), "precondition: running");

        float prev = anim.cycle();
        float total = 0f;
        int steps = 0;
        for (float speed = decelerate(240f);
             anim.activity() == TfAnimState.Activity.RUN && steps < 500;
             speed = decelerate(speed)) {
            now += DT;
            step(anim, speed, now);
            if (anim.activity() != TfAnimState.Activity.RUN) break;
            float c = anim.cycle();
            float d = c - prev;
            if (d < 0f) d += 1f;                       // the cycle wrapped
            total += d;
            prev = c;
            steps++;
        }

        // Every step advances the clip by the same dt/0.7, because the rate is pinned to 1.
        float expectedPerStep = DT / 0.7f;
        assertEquals(expectedPerStep * steps, total, 1e-3f,
                "the run clip must advance at a constant cadence while decelerating");
        assertTrue(steps > 20, "a 240 -> 0 stop should take ~31 substeps, got " + steps);
    }

    @Test
    @DisplayName("no cadence jump when the activity flips from running to idle")
    void noCadenceDiscontinuityAtTheFlip() {
        TfAnimState anim = new TfAnimState();
        float now = DT;
        step(anim, 240f, now);

        float speed = 240f;
        float beforeFlip = 0f;
        for (int i = 0; i < 500; i++) {
            float prevCycle = anim.cycle();
            speed = decelerate(speed);
            now += DT;
            step(anim, speed, now);
            if (anim.activity() != TfAnimState.Activity.RUN) {
                // The outgoing run layer keeps advancing while it fades. Source's transitioner
                // stores the playback rate at the moment of the change, and for TF that is 1.
                float afterFlip = anim.prevCycle();
                float outgoingRate = afterFlip - prevCycle;
                if (outgoingRate < 0f) outgoingRate += 1f;
                assertEquals(1.0f, outgoingRate / Math.max(beforeFlip, 1e-6f), 0.05f,
                        "the outgoing run clip changed cadence at the flip");
                return;
            }
            float c = anim.cycle();
            beforeFlip = c - prevCycle;
            if (beforeFlip < 0f) beforeFlip += 1f;
        }
        throw new AssertionError("never stopped running");
    }

    @Test
    @DisplayName("an ordinary activity change carries the cycle over instead of restarting it")
    void activityChangeDoesNotRestartTheCycle() {
        TfAnimState anim = new TfAnimState();
        float now = 0f;
        // Run long enough to be well inside the clip.
        for (int i = 0; i < 20; i++) {
            now += DT;
            step(anim, 240f, now);
        }
        float running = anim.cycle();
        assertTrue(running > 0.1f, "precondition: mid-clip, got " + running);

        now += DT;
        step(anim, 0f, now);          // stop dead
        assertEquals(TfAnimState.Activity.STAND_IDLE, anim.activity());

        // ResetSequence is SetSequence + ResetSequenceInfo on the client — it never writes the
        // cycle. Only RestartMainSequence does, and run<->idle is not one of its call sites.
        assertEquals(running + DT / 1.6667f, anim.cycle(), 1e-3f,
                "the cycle must carry over into the idle, not restart at 0");
        // prevCycle is captured before the flip frame's advance and then advanced once by the
        // cross-fade in that same frame, so it sits exactly one run-step past the displayed pose.
        assertEquals(running + DT / 0.7f, anim.prevCycle(), 1e-3f,
                "the outgoing layer starts from the pose actually being displayed");
    }

    @Test
    @DisplayName("landing cross-fades from the pose on screen, not from frame 0")
    void landingKeepsTheOutgoingCycle() {
        TfAnimState anim = new TfAnimState();
        float now = 0f;
        step(anim, 0f, now);

        anim.onJump(now);
        // Airborne for a while so the jump clip is well into its cycle.
        for (int i = 0; i < 30; i++) {
            now += DT;
            anim.update(0f, 200f, false, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        }
        float airborneCycle = anim.cycle();
        assertTrue(airborneCycle > 0.05f, "precondition: mid-clip, got " + airborneCycle);

        // Touch down. TF2 zeroes the main cycle here, and the cross-fade must not read that zero.
        now += DT;
        anim.update(0f, 0f, true, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);

        assertEquals(airborneCycle, anim.prevCycle(), DT / 0.7f + 1e-3f,
                "the outgoing layer must start from the pose that was actually on screen");
        assertTrue(anim.prevCycle() > 0.05f,
                "prevCycle came back as " + anim.prevCycle() + " — it read the zeroed cycle");
    }

    @Test
    @DisplayName("both touchdown paths fire the land gesture, crouched and not")
    void bothLandingPathsFireTheGesture() {
        // An uncrouched rocket jump exceeds velZ > 300 and lands through the AIRWALK exit; a
        // crouched one fails that branch's !ducked gate and lands through the JUMP exit.
        TfAnimState airwalk = new TfAnimState();
        float now = 0f;
        step(airwalk, 0f, now);
        for (int i = 0; i < 10; i++) {
            now += DT;
            airwalk.update(0f, 400f, false, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        }
        assertEquals(TfAnimState.Activity.AIRWALK, airwalk.activity(), "precondition: airwalking");
        now += DT;
        airwalk.update(0f, 0f, true, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        assertTrue(airwalk.landActive(), "the airwalk landing must fire the gesture");

        TfAnimState jump = new TfAnimState();
        now = 0f;
        step(jump, 0f, now);
        jump.onJump(now);
        for (int i = 0; i < 20; i++) {
            now += DT;
            jump.update(0f, 200f, false, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        }
        now += DT;
        jump.update(0f, 0f, true, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        assertTrue(jump.landActive(), "the plain jump landing must fire it too");
    }

    @Test
    @DisplayName("the land gesture runs exactly one second and then retires itself")
    void landGestureRunsOneSecond() {
        TfAnimState anim = new TfAnimState();
        float now = 0f;
        step(anim, 0f, now);
        anim.onJump(now);
        for (int i = 0; i < 20; i++) {
            now += DT;
            anim.update(0f, 200f, false, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        }
        now += DT;
        anim.update(0f, 0f, true, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        assertTrue(anim.landActive());

        // @jumpland_PRIMARY is 31 frames at 30 fps, exactly 1.0 s, and ANIM_LAYER_AUTOKILL retires
        // it at cycle 1. The clip's delta is identity at both ends, so there is no ramp.
        int steps = 0;
        while (anim.landActive() && steps < 500) {
            now += DT;
            anim.update(0f, 0f, true, false, 0f, 0f, 0f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
            steps++;
        }
        assertEquals(1.0f, steps * DT, 0.02f, "one second, then gone");
    }

    @Test
    @DisplayName("a standing turn leaves the feet 45 degrees short of the view, as TF2 does")
    void standingTurnStopsFortyFiveDegreesShort() {
        TfAnimState anim = new TfAnimState();
        float now = 0f;
        step(anim, 0f, now);                       // initialises the feet to the view

        // Snap the view 180 degrees and hold it, standing still. ComputePoseParam_AimYaw steps
        // m_flGoalFeetYaw by 45 degrees only while the delta exceeds 45, so the goal lands 45 short
        // and stays there; TF2 has no mp_facefronttime to unwind it.
        for (int i = 0; i < 400; i++) {
            now += DT;
            anim.update(0f, 0f, true, false, 180f, 0f, 180f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        }

        float offset = Math.abs(TfAnimState.normalize180(180f - anim.currentFeetYaw()));
        assertEquals(45f, offset, 1.0f, "TF2 leaves the feet exactly 45 degrees short of the view");
        assertEquals(45f, Math.abs(anim.poseBodyYaw()), 1.0f,
                "and the torso holds the residual twist, saturating the aim matrix");
    }

    @Test
    @DisplayName("moving snaps the feet goal onto the view, unwinding the standing offset")
    void movingRealignsTheFeet() {
        TfAnimState anim = new TfAnimState();
        float now = 0f;
        step(anim, 0f, now);
        for (int i = 0; i < 400; i++) {          // build up the standing 45 degree offset
            now += DT;
            anim.update(0f, 0f, true, false, 180f, 0f, 180f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        }
        assertTrue(Math.abs(anim.poseBodyYaw()) > 40f, "precondition: torso is twisted");

        // The feet match the eye direction while moving; the move yaw carries the rest.
        for (int i = 0; i < 200; i++) {
            now += DT;
            anim.update(240f, 0f, true, false, 180f, 0f, 180f, now, DT, RUN_BOX_EDGE, CROUCH_BOX_EDGE);
        }
        assertEquals(0f, anim.poseBodyYaw(), 1.0f, "running must unwind the torso onto the view");
    }
}
