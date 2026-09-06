package com.soldiermc.client;

import java.util.Locale;

import com.soldiermc.SoldierMC;
import com.soldiermc.source.SourceFeel;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/** Debug overlay printing measured values beside their expected ones. Toggled by a keybind. */
public final class SoldierDiagnostics {

    private static final int TEXT = 0xFFFFFFFF;
    private static final int DIM = 0xFFA0A0A0;
    private static final int GOOD = 0xFF70FF70;
    private static final int BAD = 0xFFFF6060;

    // ---- expected values ----

    /** Ground speed, hu/s: the class max. */
    private static final float EXPECT_GROUND_SPEED = 240.0f;

    /**
     * Peak velZ inside the jump substep: 289 minus three half-steps of gravity — StartGravity,
     * FinishGravity inside CheckJumpButton, FinishGravity again at the end of FullWalkMove.
     */
    private static final float EXPECT_LAUNCH_VZ = 271.0f;

    /** Apex of a plain jump, hu. */
    private static final float EXPECT_APEX_HU = 50.04f;

    /** PreventBunnyJumping clamps the 3D velocity to 1.2 x 240 on every ground jump. */
    private static final float EXPECT_BUNNY_CAP = 288.0f;

    /**
     * A grounded stand-and-shoot rocket jump into a flat floor. Not the 448 reference tables quote:
     * the muzzle sits 12.37 hu off the player's axis, so the rocket detonates 12.13 hu away and
     * falloff takes 4.5 damage off before the force is computed.
     */
    private static final float EXPECT_GROUNDED_BLAST_FORCE = 427.44f;

    /** {@code tf_weaponbase.h:63-66}. */
    private static final String[] RELOAD_MODES = {"idle", "reload start", "reloading", "reload end"};

    // ---- live state ----
    private static float peakSpeed;
    private static float lastApexHu;
    private static float bestApexHu;
    private static float groundZHu;
    private static boolean wasAirborne;
    private static int airTicks;
    private static int lastAirTicks;

    private SoldierDiagnostics() {
    }

    public static void init() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, "diagnostics"),
                SoldierDiagnostics::render);
        ClientTickEvents.END_CLIENT_TICK.register(SoldierDiagnostics::tick);
    }

    private static void tick(Minecraft mc) {
        if (mc.player == null || !SoldierMovement.isActive()) return;

        float speed = SoldierMovement.horizontalSpeed();
        if (speed > peakSpeed) peakSpeed = speed;

        boolean airborne = !SoldierMovement.onGround();
        float zHu = SourceFeel.toUnits(mc.player.getY());

        if (airborne && !wasAirborne) {
            groundZHu = zHu;
            airTicks = 0;
            SoldierMovement.clearPeakVelZ();
        } else if (airborne) {
            airTicks++;
            float rise = zHu - groundZHu;
            if (rise > lastApexHu) lastApexHu = rise;
        } else if (wasAirborne) {
            lastAirTicks = airTicks;
            if (lastApexHu > bestApexHu) bestApexHu = lastApexHu;
        }

        if (!airborne && wasAirborne) {
            // landed: freeze lastApexHu until the next takeoff
        } else if (!airborne) {
            lastApexHu = Math.max(lastApexHu, 0f);
        }
        wasAirborne = airborne;
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        final Minecraft mc = Minecraft.getInstance();
        // F1 already suppresses the whole HUD layer stack, so there is no hideGui check.
        if (mc.player == null || !SoldierClient.diagnostics()) return;

        final boolean on = SoldierMovement.isActive();

        int y = 4;
        y = line(g, mc, y, "SOURCE PHYSICS " + (on ? "ON" : "off"), on ? GOOD : DIM);
        if (!on) {
            line(g, mc, y, "vanilla movement - P to re-enable", DIM);
            return;
        }

        final float speed = SoldierMovement.horizontalSpeed();
        final float launch = SoldierMovement.peakVelZ();
        final boolean ground = SoldierMovement.onGround();

        y = line(g, mc, y, fmt("state      %-8s   sf %.2f  %s",
                ground ? "GROUND" : "AIR",
                SoldierMovement.surfaceFriction(),
                SoldierMovement.surfaceFriction() < 0.5f ? "(rising: air control x0.25)" : ""),
                TEXT);

        y = line(g, mc, y, fmt("speed    %7.1f hu/s (%.2f b/s)",
                speed, SourceFeel.toBlocks(speed)), TEXT);
        y = line(g, mc, y, fmt("  peak   %7.1f     exp %.0f ground / %.0f bhop",
                peakSpeed, EXPECT_GROUND_SPEED, EXPECT_BUNNY_CAP),
                near(peakSpeed, EXPECT_GROUND_SPEED, 0.5f) || near(peakSpeed, EXPECT_BUNNY_CAP, 1.0f)
                        ? GOOD : DIM);

        y = line(g, mc, y, fmt("launch   %7.1f hu/s  exp %.1f", launch, EXPECT_LAUNCH_VZ),
                launch <= 0f ? DIM : near(launch, EXPECT_LAUNCH_VZ, 1.0f) ? GOOD : BAD);

        y = line(g, mc, y, fmt("apex     %7.2f hu   (%.3f b)  exp %.2f hu",
                lastApexHu, SourceFeel.toBlocks(lastApexHu), EXPECT_APEX_HU),
                lastApexHu <= 0f ? DIM : near(lastApexHu, EXPECT_APEX_HU, 1.5f) ? GOOD : TEXT);
        y = line(g, mc, y, fmt("  best   %7.2f hu   (%.3f b)",
                bestApexHu, SourceFeel.toBlocks(bestApexHu)), DIM);

        y = line(g, mc, y, fmt("airtime  %7d tk   (%.2f s)", lastAirTicks, lastAirTicks / 20.0f), TEXT);

        // R is the input rate, which sets the air-strafe gain ceiling.
        int r = SoldierMovement.freshInputRate();
        y = line(g, mc, y, fmt("substeps %7d      (4/3/3%s)",
                SoldierMovement.lastSubstepCount(),
                SoldierMovement.lastDropped() > 0
                        ? ", DROPPED " + SoldierMovement.lastDropped() : ""),
                SoldierMovement.lastDropped() > 0 ? BAD : DIM);
        y = line(g, mc, y, fmt("input R  %7d /s   (>60 good, 20 = broken)", r),
                r >= 60 ? GOOD : r <= 25 ? BAD : TEXT);

        y = line(g, mc, y, fmt("scale    %7.4f hu/blk", SourceFeel.UNITS_PER_BLOCK), DIM);

        // A frozen cycle means the animation clock is dead, not the decoder.
        String act = SoldierBody.lastActivity();
        y = line(g, mc, y, fmt("anim     %-12s cycle %.2f  rate %.2f",
                act, SoldierBody.lastCycle(), SoldierBody.lastRate()),
                "-".equals(act) ? DIM : GOOD);

        // eye is the absolute look yaw in Source degrees, feet is the model's own rotation, body_*
        // are the aim matrix's pose parameters: body_yaw is eye-minus-feet negated, body_pitch is
        // negative when looking down.
        y = line(g, mc, y, fmt("aim yaw  eye %7.1f  feet %7.1f  body %6.1f",
                SoldierBody.lastEyeYaw(), SoldierBody.lastFeetYaw(), SoldierBody.lastBodyYaw()),
                TEXT);
        float bp = SoldierBody.lastBodyPitch();
        y = line(g, mc, y, fmt("aim pitch  xRot %6.1f  body %6.1f  (%s)",
                mc.player.getXRot(), bp,
                bp > 5f ? "up" : bp < -5f ? "down" : "level"), TEXT);

        // ---- the rocket launcher ----
        y = line(g, mc, y, fmt("clip     %4d / 4   reserve %2d   %-18s live %d",
                SoldierMovement.clip(), SoldierMovement.reserveAmmo(),
                RELOAD_MODES[Math.min(SoldierMovement.reloadMode(), 3)],
                SoldierMovement.liveRockets()), TEXT);

        // The muzzle throws the rocket 12 hu off axis, so a shot at your own feet never detonates
        // on the axis and never reaches a reference table's 448.
        float f = SoldierMovement.lastBlastForce();
        y = line(g, mc, y, fmt("blast    d %5.1f hu  dmg %5.1f (-%d hp)  F %6.1f  exp %.1f",
                SoldierMovement.lastBlastDistance(), SoldierMovement.lastBlastDamage(),
                SoldierMovement.lastHealthLost(), f, EXPECT_GROUNDED_BLAST_FORCE),
                f <= 0f ? DIM : near(f, EXPECT_GROUNDED_BLAST_FORCE, 2.0f) ? GOOD : TEXT);

        // Whether the audio pack loaded, and what the last voice attempt did.
        String reason = SoldierVoice.lastReason();
        line(g, mc, y, fmt("voice    pack %-3s  menu %s  last %-22s %s",
                SoldierResourcePack.installed() ? "ON" : "off",
                SoldierVoice.openMenu() < 0 ? "-" : String.valueOf(SoldierVoice.openMenu() + 1),
                SoldierVoice.lastLine(), reason),
                SoldierResourcePack.installed() ? (reason.isEmpty() ? GOOD : TEXT) : BAD);
    }

    private static boolean near(float actual, float expected, float tolerance) {
        return Math.abs(actual - expected) <= tolerance;
    }

    /** Locale.ROOT so a machine with a comma decimal separator still prints 142.7, not 142,7. */
    private static String fmt(String pattern, Object... args) {
        return String.format(Locale.ROOT, pattern, args);
    }

    private static int line(GuiGraphicsExtractor g, Minecraft mc, int y, String text, int color) {
        g.text(mc.font, text, 4, y, color);
        return y + 10;
    }
}
