package com.soldiermc.source;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The firing and reload timing table, in exact substep indices rather than seconds. {@code t_k =
 * 0.015k} and every gate is {@code <=} or {@code >} against a quantised curtime, so the answer is
 * always the next substep at or after the deadline.
 */
class WeaponTimingTest {

    private static final float DT = 0.015f;

    /** Drives the weapon exactly one call per substep, as Source does. */
    private static final class Rig {
        final GunStats stats = GunStats.ROCKET_LAUNCHER;
        final TFWeaponGun gun = new TFWeaponGun(stats, TfAmmo.PRIMARY);
        final PlayerAmmo ammo = new PlayerAmmo();
        final MoveData mv = new MoveData();
        final List<Integer> shotSubsteps = new ArrayList<>();
        final List<Integer> ammoEventSubsteps = new ArrayList<>();
        int substep;

        Rig(int reserve) {
            ammo.setMax(TfAmmo.PRIMARY, 20);
            ammo.set(TfAmmo.PRIMARY, reserve);
        }

        final ProjectileSink sink = new ProjectileSink() {
            @Override
            public void fireRocket(float curtime, MoveData mv, boolean ducked, boolean critical) {
                shotSubsteps.add(substep);
            }

            @Override
            public void weaponSound(int sound) {
            }
        };

        /** @return the substep index just run */
        int step(int buttons) {
            int before = gun.ammoEvents();
            gun.runCommand(substep * DT, buttons, ammo, sink, mv, false);
            if (gun.ammoEvents() > before) ammoEventSubsteps.add(substep);
            return substep++;
        }

        void run(int buttons, int n) {
            for (int i = 0; i < n; i++) step(buttons);
        }
    }

    @Test
    @DisplayName("sustained fire spaces shots exactly 54 substeps apart")
    void sustainedFireCadence() {
        Rig r = new Rig(20);
        r.run(Buttons.IN_ATTACK, 200);

        assertEquals(List.of(0, 54, 108, 162), r.shotSubsteps.subList(0, 4),
                "0.8 s at 0.015 per substep is 53.33, so the shot lands on the next substep AT OR "
                        + "AFTER the deadline — 54, not 53");
        assertEquals(4, r.gun.shotsFired() - countAfter(r.shotSubsteps, 162),
                "the clip is four rockets");
    }

    private static int countAfter(List<Integer> xs, int after) {
        int n = 0;
        for (int x : xs) if (x > after) n++;
        return n;
    }

    @Test
    @DisplayName("a held reload from a full-clip shot: the rocket lands at substep 111")
    void singleReloadLandsOnTheAnimEvent() {
        Rig r = new Rig(20);

        // Fire once at substep 0 with +reload already held, clip 4 -> 3.
        int buttons = Buttons.IN_ATTACK | Buttons.IN_RELOAD;
        r.step(buttons);
        assertEquals(3, r.gun.clip(), "the shot came out of the clip");

        // Release the trigger, hold reload.
        r.run(Buttons.IN_RELOAD, 200);

        assertEquals(List.of(111), r.ammoEventSubsteps.subList(0, 1),
                "AE_WPN_INCREMENTAMMO at cycle 10/24 of the 0.8 s loop, on the substep grid");
        assertEquals(4, r.gun.clip(), "the rocket is back in the clip");
        assertEquals(19, r.ammo.get(TfAmmo.PRIMARY), "and out of the reserve");
    }

    @Test
    @DisplayName("per-rocket cadence is exactly 55 substeps — 0.825 s, not 0.810 and not 0.840")
    void perRocketCadence() {
        Rig r = new Rig(20);
        r.gun.setClip(0);
        r.run(Buttons.IN_RELOAD, 400);

        assertTrue(r.ammoEventSubsteps.size() >= 4,
                "expected four rockets, got " + r.ammoEventSubsteps);

        List<Integer> got = r.ammoEventSubsteps.subList(0, 4);
        for (int i = 1; i < got.size(); i++) {
            int gap = got.get(i) - got.get(i - 1);
            assertEquals(55, gap,
                    "gap " + i + " was " + gap + " substeps (" + (gap * DT) + " s). 54 means "
                            + "TF_RELOADING_CONTINUE collapsed into TF_RELOADING; 56 means the "
                            + "viewmodel's 0.833333 loop was used instead of the c_model's 0.8");
        }
        assertEquals(4, r.gun.clip());
        assertEquals(16, r.ammo.get(TfAmmo.PRIMARY));
    }

    @Test
    @DisplayName("cancelling after the clunk keeps the rocket; cancelling before it does not")
    void theCancelWindow() {
        // The rocket enters the clip 0.333 s into a 0.8 s loop, leaving 0.467 s in which letting go
        // still keeps it.
        Rig probe = new Rig(20);
        probe.gun.setClip(0);
        probe.run(Buttons.IN_RELOAD, 200);
        int clunk = probe.ammoEventSubsteps.get(0);
        assertEquals(57, clunk, "first rocket from empty, no prior shot: 0.5 start then 0.333 in");

        Rig keep = new Rig(20);
        keep.gun.setClip(0);
        keep.run(Buttons.IN_RELOAD, clunk + 1);
        assertEquals(1, keep.gun.clip(), "released one substep after the clunk: the rocket is kept");

        Rig lose = new Rig(20);
        lose.gun.setClip(0);
        lose.run(Buttons.IN_RELOAD, clunk);
        assertEquals(0, lose.gun.clip(), "released one substep before it: nothing is kept");
    }

    @Test
    @DisplayName("M2 locks out primary fire after half a second, but not instantly")
    void secondaryBlocksPrimary() {
        // SecondaryAttack is semi-auto: the first command with M2 down passes the gate, blocking
        // that command, and stamps nextSecondaryAttack = +0.5, so primary is free for 0.5 s. The
        // latch never re-stamps the timer, so after that primary is blocked while M2 is held.
        Rig r = new Rig(20);
        r.run(Buttons.IN_ATTACK | Buttons.IN_ATTACK2, 200);

        assertEquals(List.of(1), r.shotSubsteps,
                "exactly one rocket escapes, in the 0.5 s window before the latch re-opens the gate");

        Rig plain = new Rig(20);
        plain.run(Buttons.IN_ATTACK, 200);
        assertEquals(4, plain.gun.shotsFired(), "control: M1 alone empties the clip");
    }

    @Test
    @DisplayName("deploy is 0.5 s, and holster cancels a running reload's gate")
    void deployAndHolster() {
        Rig r = new Rig(20);
        r.gun.deploy(0f);
        assertEquals(0.5f, r.gun.nextPrimaryAttack(), 1e-6f, "DefaultDeploy's 0.8 never survives");

        r.run(Buttons.IN_ATTACK, 60);
        assertEquals(34, r.shotSubsteps.get(0),
                "0.5 s is 33.33 substeps, so the first shot is at 34");

        Rig h = new Rig(20);
        h.gun.setClip(0);
        h.run(Buttons.IN_RELOAD, 60);
        assertTrue(h.gun.playerNextAttack() > 60 * DT, "precondition: mid-reload, gated");
        h.gun.holster(60 * DT);
        assertEquals(60 * DT, h.gun.playerNextAttack(), 1e-6f, "holster clears the gate");
        assertEquals(TFWeaponGun.TF_RELOAD_START, h.gun.reloadMode());
    }

    @Test
    @DisplayName("an empty reserve stops the reload rather than looping forever")
    void emptyReserveTerminates() {
        Rig r = new Rig(2);
        r.gun.setClip(0);
        r.run(Buttons.IN_RELOAD, 400);
        assertEquals(2, r.gun.clip(), "only what the reserve held");
        assertEquals(0, r.ammo.get(TfAmmo.PRIMARY));
        assertEquals(TFWeaponGun.TF_RELOAD_START, r.gun.reloadMode(), "and it settled back to idle");
    }
}
