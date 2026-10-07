package com.soldiermc.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RocketJumpTest {

    private static final float DT = SourceFeel.DT;
    private static final int SHOOTER = 1;
    private static final float BASE_DAMAGE = 90.0f;

    private static RocketSpawn fireDown(FlatWorld world, boolean ducked, float pitch) {
        FireSetup setup = new FireSetup();
        RocketSpawn spawn = new RocketSpawn();
        setup.fireRocket(world, 0f, 0f, 0f, pitch, 0f, ducked, SHOOTER, spawn);
        return spawn;
    }

    @Test
    @DisplayName("the muzzle sits off the player's axis, which is why d is never zero")
    void muzzleIsOffAxis() {
        FlatWorld world = new FlatWorld();
        RocketSpawn s = fireDown(world, false, 90f);

        float offAxis = (float) Math.hypot(s.muzzleX, s.muzzleY);
        assertEquals(12.369317f, offAxis, 1e-3f,
                "the (23.5, 12, -3) offset in the eye basis, looking straight down");
        assertEquals(44.5f, s.muzzleZ, 1e-3f, "eye 68 minus the 23.5 forward now pointing down");
    }

    @Test
    @DisplayName("a rocket fired at the floor detonates just above it, pulled back along the normal")
    void detonatesAboveTheFloor() {
        FlatWorld world = new FlatWorld();
        RocketSpawn s = fireDown(world, false, 90f);

        RocketFlight r = new RocketFlight();
        r.launch(s, SHOOTER, BASE_DAMAGE, false);

        boolean boom = false;
        for (int i = 0; i < 100 && !boom; i++) boom = r.step(world, DT);
        assertTrue(boom, "the rocket must reach the floor");

        // EXPLOSION_PULLOUT along the floor normal.
        assertEquals(1.0f, r.blastZ, 1e-3f, "detonation is pulled 1 hu out of the surface");
        assertEquals(-1, r.hitEntityId, "it hit the world, not an entity");
    }

    @Test
    @DisplayName("the rocket never hits its own shooter, even spawning inside his hull")
    void ownerIsExcluded() {
        // The rocket spawns inside the shooter's hull; the trace filter skips the owner.
        FlatWorld world = new FlatWorld();
        RocketSpawn s = fireDown(world, false, 90f);
        RocketFlight r = new RocketFlight();
        r.launch(s, SHOOTER, BASE_DAMAGE, false);

        for (int i = 0; i < 100; i++) {
            if (r.step(world, DT)) break;
        }
        assertEquals(-1, r.hitEntityId, "the shooter must never be the hit entity");
    }

    @Test
    @DisplayName("the real stand-and-shoot rocket jump: ~415 hu/s, not the 448 every table quotes")
    void groundedStandAndShoot() {
        FlatWorld world = new FlatWorld();
        RocketSpawn s = fireDown(world, false, 90f);

        RocketFlight r = new RocketFlight();
        r.launch(s, SHOOTER, BASE_DAMAGE, false);
        for (int i = 0; i < 100; i++) {
            if (r.step(world, DT)) break;
        }

        SelfBlast blast = new SelfBlast();
        boolean hit = blast.compute(r.blastX, r.blastY, r.blastZ, 0f, 0f, 0f,
                false, true, false, false, BASE_DAMAGE);
        assertTrue(hit, "the blast must reach the player");

        assertEquals(12.132f, blast.falloffDistance, 0.05f, "distance from the blast to the player");
        assertEquals(85.488f, blast.damage, 0.05f);
        assertEquals(427.440f, blast.forceMagnitude(), 0.5f);
        assertEquals(415.466f, blast.forceZ, 0.5f, "the number the panel reads");
        assertTrue(blast.forceZ < 448f,
                "if this reaches 448 the muzzle offset has been dropped and the rocket is "
                        + "detonating on the player's own axis");
    }

    @Test
    @DisplayName("pitch 89 is the reachable maximum, and it costs one more HP than pitch 90")
    void reachablePitchCostsOneMoreHitPoint() {
        // cl_pitchdown caps TF2 at 89 (FCVAR_CHEAT); Minecraft reaches 90.
        FlatWorld world = new FlatWorld();

        RocketSpawn ninety = fireDown(world, false, 90f);
        RocketSpawn real = fireDown(world, false, SourceFeel.PITCH_CLAMP);

        SelfBlast a = blastFor(world, ninety);
        SelfBlast b = blastFor(world, real);

        assertEquals(85, a.healthLost, "pitch 90, the unreachable idealisation");
        assertEquals(86, b.healthLost, "pitch 89, what a player actually gets");
        assertTrue(b.forceZ > a.forceZ, "and marginally more lift");
    }

    private static SelfBlast blastFor(FlatWorld world, RocketSpawn s) {
        RocketFlight r = new RocketFlight();
        r.launch(s, SHOOTER, BASE_DAMAGE, false);
        for (int i = 0; i < 100; i++) {
            if (r.step(world, DT)) break;
        }
        SelfBlast blast = new SelfBlast();
        blast.compute(r.blastX, r.blastY, r.blastZ, 0f, 0f, 0f, false, true, false, false, BASE_DAMAGE);
        return blast;
    }

    @Test
    @DisplayName("an airborne rocket jump lifts harder than a grounded one, in the ratio 6:5")
    void airborneLiftsHarder() {
        FlatWorld world = new FlatWorld();
        RocketSpawn s = fireDown(world, false, 90f);
        RocketFlight r = new RocketFlight();
        r.launch(s, SHOOTER, BASE_DAMAGE, false);
        for (int i = 0; i < 100; i++) {
            if (r.step(world, DT)) break;
        }

        SelfBlast grounded = new SelfBlast();
        grounded.compute(r.blastX, r.blastY, r.blastZ, 0f, 0f, 0f, false, true, false, false, BASE_DAMAGE);
        SelfBlast airborne = new SelfBlast();
        airborne.compute(r.blastX, r.blastY, r.blastZ, 0f, 0f, 0f, false, false, false, false, BASE_DAMAGE);

        assertEquals(6.0f / 5.0f, airborne.forceMagnitude() / grounded.forceMagnitude(), 1e-4f);
    }
}
