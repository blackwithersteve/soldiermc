package com.soldiermc.source;

import java.util.Locale;

/**
 * Prints the ported simulation's movement readings, with no Minecraft and no game. Run:
 * {@code java -cp <classes> com.soldiermc.source.MovementHarness}
 */
public final class MovementHarness {

    private static int failures = 0;

    public static void main(String[] args) {
        System.out.printf(Locale.ROOT, "SoldierMC movement harness — dt=%.3f  S=%.4f hu/block%n%n",
                SourceFeel.DT, SourceFeel.UNITS_PER_BLOCK);

        groundTopSpeed();
        plainJump();
        airStrafe();
        bunnyCap();
        wallSlideCanary();

        System.out.println();
        if (failures == 0) {
            System.out.println("ALL CHECKS PASSED");
        } else {
            System.out.println(failures + " CHECK(S) FAILED");
            System.exit(1);
        }
    }

    // ---- scenarios ----

    /** Holding W on flat ground settles at the class max, 240 hu/s. */
    private static void groundTopSpeed() {
        Sim s = new Sim();
        for (int i = 0; i < 400; i++) {
            s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
            s.step();
        }
        check("ground top speed", s.mv.speed2D(), 240.0f, 0.5f, "hu/s");
    }

    /**
     * A plain jump launches at 271 hu/s, apexes near 50 hu, and is airborne about 0.72 s. 271 rather
     * than 289 because three half-steps of gravity land on the jump substep: StartGravity (-6),
     * FinishGravity inside CheckJumpButton (-6), FinishGravity at the end of FullWalkMove (-6).
     */
    private static void plainJump() {
        Sim s = new Sim();
        for (int i = 0; i < 20; i++) s.step();   // settle on the ground

        s.mv.buttons = Buttons.IN_JUMP;
        s.step();
        float launch = s.mv.velZ;
        s.mv.buttons = 0;

        float apex = 0f;
        int airTicks = 0;
        for (int i = 0; i < 200; i++) {
            s.step();
            apex = Math.max(apex, s.mv.originZ);
            if (!s.move.onGround()) airTicks++;
            else if (i > 2) break;
        }

        check("jump launch velZ", launch, 271.0f, 1.0f, "hu/s");
        check("jump apex", apex, 50.04f, 1.5f, "hu");
        System.out.printf(Locale.ROOT, "     apex in blocks   = %.3f b  (airborne %.3f s)%n",
                apex / SourceFeel.UNITS_PER_BLOCK, airTicks * SourceFeel.DT);
    }

    /** Strafe key only, turning the view: speed must rise above the 240 ground cap. */
    private static void airStrafe() {
        // Strafing left (A) pairs with turning left, and vice versa. In Source's basis +Y is left
        // and right = (sin yaw, -cos yaw), so exactly one pairing gains speed.
        float leftPair = strafeRun(-SourceFeel.CL_SIDESPEED, +1.6f);
        float rightPair = strafeRun(+SourceFeel.CL_SIDESPEED, -1.6f);
        float wrongPair = strafeRun(-SourceFeel.CL_SIDESPEED, -1.6f);

        float best = Math.max(leftPair, rightPair);
        boolean gains = best > 240.0f;
        boolean wrongWayLoses = wrongPair <= best;

        System.out.printf(Locale.ROOT, "%s air-strafe peak    = %9.3f hu/s (must exceed 240)%n",
                gains ? "[ok]  " : "[FAIL]", best);
        System.out.printf(Locale.ROOT, "       A+left %.2f | D+right %.2f | A+right %.2f (mismatched must lose)%n",
                leftPair, rightPair, wrongPair);
        if (!gains) failures++;
        if (!wrongWayLoses) {
            System.out.println("[FAIL] mismatched strafe gained as much as matched — basis is wrong");
            failures++;
        }
    }

    /** Build ground speed, jump, then hold one strafe key while sweeping the view. */
    private static float strafeRun(float sideMove, float yawRate) {
        Sim s = new Sim();

        // Air strafing redirects momentum rather than creating it, so reach the 240 cap first.
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
            s.mv.forwardMove = 0f;
            s.mv.sideMove = sideMove;
            s.mv.viewYaw += yawRate;
            s.step();
            peak = Math.max(peak, s.mv.speed2D());
        }
        return peak;
    }

    /** PreventBunnyJumping clamps the whole 3D velocity to 1.2 x 240 = 288 hu/s on a ground jump. */
    private static void bunnyCap() {
        Sim s = new Sim();
        for (int i = 0; i < 20; i++) s.step();

        // Inject a large horizontal velocity, then jump from the ground.
        s.mv.velX = 900.0f;
        s.mv.buttons = Buttons.IN_JUMP;
        s.step();

        float after = s.mv.speed2D();
        check("bunnyhop clamp", after, 288.0f, 1.0f, "hu/s");
    }

    /** Sliding along a flat wall at a shallow angle: 200 substeps at 240 hu/s = 720 hu of travel. */
    private static void wallSlideCanary() {
        Sim s = new Sim();
        s.world.wallX = 0f;
        s.mv.originX = -10f;

        for (int i = 0; i < 20; i++) s.step();

        float startY = s.mv.originY;
        for (int i = 0; i < 200; i++) {
            // Push into the wall at 5 degrees off parallel.
            s.mv.viewYaw = 85f;
            s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
            s.step();
        }
        float travelled = Math.abs(s.mv.originY - startY);
        float ideal = 240.0f * 200 * SourceFeel.DT;

        // Allow for the acceleration ramp at the start.
        boolean ok = travelled > ideal * 0.85f;
        System.out.printf(Locale.ROOT, "%s wall slide travel  = %.1f hu  (ideal %.1f, >85%% required)%n",
                ok ? "[ok]  " : "[FAIL]", travelled, ideal);
        if (!ok) failures++;
    }

    // ---- plumbing ----

    private static void check(String name, float actual, float expected, float tol, String unit) {
        boolean ok = Math.abs(actual - expected) <= tol;
        System.out.printf(Locale.ROOT, "%s %-18s = %9.3f %-5s (expect %.3f +/- %.2f)%n",
                ok ? "[ok]  " : "[FAIL]", name, actual, unit, expected, tol);
        if (!ok) failures++;
    }

    /** One simulated Soldier on flat ground. */
    private static final class Sim {
        final FlatWorld world = new FlatWorld();
        final SourceMovement move = new SourceMovement(world);
        final MoveData mv = new MoveData();

        Sim() {
            mv.clientMaxSpeed = SourceFeel.CLASS_MAX_SPEED;
            mv.maxSpeed = SourceFeel.TF_MAX_SPEED;
            mv.originZ = 0f;
            move.reset(true);
        }

        void step() {
            move.processMovement(mv);
            mv.forwardMove = 0f;
            mv.sideMove = 0f;
        }
    }

    private MovementHarness() {
    }
}
