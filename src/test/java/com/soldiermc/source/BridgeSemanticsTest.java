package com.soldiermc.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeSemanticsTest {

    @Test
    @DisplayName("walking into a block does not build momentum")
    void wallDoesNotBuildMomentumUnderMinecraftCollision() {
        Sim s = new Sim();
        s.world.wallXBlocks = 0.0;
        s.mv.originX = SourceFeel.toUnits(-3.0);
        s.mv.viewYaw = 0f;
        for (int i = 0; i < 20; i++) s.step();

        float peak = 0f;
        boolean everAirborne = false;
        for (int i = 0; i < 200; i++) {
            s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
            s.step();
            peak = Math.max(peak, s.mv.speed());
            if (!s.move.onGround()) everAirborne = true;
        }

        assertTrue(peak <= 245.0f,
                "speed grew while pushing into a block: " + peak
                        + " hu/s. Airborne at some point: " + everAirborne
                        + " (airborne means airAccelerate ran, which has no 240 cap)");
    }

    @Test
    @DisplayName("standing on flat ground stays grounded, never flickering to airborne")
    void staysGroundedWhenWalking() {
        Sim s = new Sim();
        for (int i = 0; i < 20; i++) s.step();

        int airborneTicks = 0;
        for (int i = 0; i < 200; i++) {
            s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
            s.step();
            if (!s.move.onGround()) airborneTicks++;
        }
        assertTrue(airborneTicks == 0,
                "went airborne " + airborneTicks + " substeps while walking on flat ground");
    }

    /** Float precision falls with distance from the origin, so far walls tunnel first. */
    @Test
    @DisplayName("the hull never crosses a block face, at any distance from world origin")
    void noTunnellingFarFromOrigin() {
        for (double originBlocks : new double[]{0.7, 64.0, 512.0, 4096.0}) {
            for (double offset : new double[]{0.0, 0.13, 0.37, 0.61, 0.89}) {
                Sim s = new Sim();
                double wall = originBlocks;
                s.world.wallXBlocks = wall;
                s.mv.originX = SourceFeel.toUnits(wall - 3.0 + offset);
                s.mv.viewYaw = 0f;
                for (int i = 0; i < 20; i++) s.step();

                float deepest = 0f;
                for (int i = 0; i < 600; i++) {
                    s.mv.forwardMove = SourceFeel.CL_FORWARDSPEED;
                    s.step();
                    // Leading face of the hull: originX plus the 0.3 block half-width.
                    double faceBlocks = SourceFeel.toBlocks(s.mv.originX) + 0.3;
                    deepest = (float) Math.max(deepest, faceBlocks - wall);
                }

                assertTrue(deepest <= 0.0f,
                        String.format(
                            "hull penetrated the block face by %.6f blocks at origin %.1f offset %.2f"
                            + ": DIST_EPSILON back-off is not holding",
                            deepest, originBlocks, offset));
            }
        }
    }

    private static final class Sim {
        final BlockWorld world = new BlockWorld();
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
