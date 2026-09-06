package com.soldiermc.client;

import com.soldiermc.bridge.InputRing;
import com.soldiermc.bridge.McWorldQuery;
import com.soldiermc.bridge.SourcePump;
import com.soldiermc.bridge.Units;
import com.soldiermc.source.Buttons;
import com.soldiermc.source.MoveData;
import com.soldiermc.source.SourceFeel;
import com.soldiermc.source.SourceMovement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.Vec3;

/**
 * Drives the ported simulation from Minecraft's client tick and writes the result back onto the
 * player; the engine owns position and velocity. Axis mapping lives in {@link Units}:
 * {@code mx = ex, my = ez, mz = -ey} with {@code sourceYaw = -mcYaw - 90}, a rotation rather than
 * the reflection {@code (ex, ey, ez) -> (ex, ez, ey)}, which flips handedness and swaps A and D.
 * Pitch shares its sign, positive down in both.
 */
public final class SoldierMovement {

    private static final McWorldQuery WORLD = new McWorldQuery();
    private static final SourceMovement SIM = new SourceMovement(WORLD);
    private static final MoveData MV = new MoveData();
    private static final SourcePump PUMP = new SourcePump();
    private static final InputRing RING = new InputRing();
    private static final InputRing.Sample SAMPLE = new InputRing.Sample();

    private static boolean active;
    private static boolean seeded;
    private static int lastFreshRate;
    private static float peakVelZ;
    private static volatile boolean jumped;

    /** Consume the one-shot jump edge. */
    public static boolean consumeJumpEdge() {
        if (!jumped) return false;
        jumped = false;
        return true;
    }

    private SoldierMovement() {
    }

    // ---- per-frame input ----

    /**
     * Record one rendered frame's input. Called from the {@code MouseHandler} mixin just after
     * Minecraft flushes mouse movement; sampling per tick instead caps the air-strafe gain at 20 Hz.
     */
    public static void sampleFrame(LocalPlayer player) {
        if (player == null) return;

        Input keys = player.input.keyPresses;

        float forward = 0f;
        if (keys.forward()) forward += SourceFeel.CL_FORWARDSPEED;
        if (keys.backward()) forward -= SourceFeel.CL_BACKSPEED;

        // Source's +Y is left, so the left key produces a negative sideMove.
        float side = 0f;
        if (keys.left()) side -= SourceFeel.CL_SIDESPEED;
        if (keys.right()) side += SourceFeel.CL_SIDESPEED;

        int buttons = 0;
        if (keys.jump()) buttons |= Buttons.IN_JUMP;
        if (keys.shift()) buttons |= Buttons.IN_DUCK;

        // Sampled per frame, so a click between two Minecraft ticks lands on the substep it
        // happened on.
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.options.keyAttack.isDown()) buttons |= Buttons.IN_ATTACK;
        if (mc.options.keyUse.isDown()) buttons |= Buttons.IN_ATTACK2;
        if (SoldierClient.reloadDown()) buttons |= Buttons.IN_RELOAD;

        RING.sample(System.nanoTime(),
                player.getXRot(),
                Units.sourceYaw(player.getYRot()),
                forward, side, buttons);
    }

    // ---- per-tick simulation ----

    /**
     * Run this Minecraft tick's Source substeps. Called from {@code Player#travel}, which is
     * cancelled so vanilla locomotion never runs.
     *
     * @return true if the simulation handled movement
     */
    public static boolean travel(LocalPlayer player) {
        if (!active) return false;

        WORLD.bind(player, player.level());
        shooterId = player.getId();

        if (!seeded) {
            seedFrom(player);
            seeded = true;
        }

        int substeps = PUMP.accrueTick();
        if (substeps <= 0) {
            writeBack(player);
            return true;
        }

        MV.clientMaxSpeed = SourceFeel.CLASS_MAX_SPEED;
        MV.maxSpeed = SourceFeel.TF_MAX_SPEED;

        for (int i = 0; i < substeps; i++) {
            // Newest sample for the last substep, walking back for earlier ones.
            int back = substeps - 1 - i;
            if (RING.sampleFromEnd(back, SAMPLE)) {
                MV.viewPitch = SAMPLE.pitch;
                MV.viewYaw = SAMPLE.yaw;
                MV.forwardMove = SAMPLE.forwardMove;
                MV.sideMove = SAMPLE.sideMove;
                MV.buttons = SAMPLE.buttons;
            }
            float t = PUMP.curtime(i);

            boolean wasGrounded = SIM.onGround();
            SIM.processMovement(MV);
            // A ground->air transition with upward velocity is the jump, and it is only visible at
            // substep resolution.
            if (wasGrounded && !SIM.onGround() && MV.velZ > 0f) jumped = true;

            // Order: movement, then rockets, then the weapon, matching Source's ProcessMovement,
            // then PostThink -> ItemPostFrame, then entity physics — which gives a new rocket a
            // one-substep head start. The FL_ONGROUND the blast reads is the one this substep's
            // categorizePosition wrote.
            stepRockets();
            WEAPON.runCommand(t, MV.buttons, AMMO, SINK, MV, SIM.ducked());

            // Sampled after the blast impulse, not straight after processMovement, where
            // startGravity and finishGravity have already taken 12 hu/s off the launch.
            if (MV.velZ > peakVelZ) peakVelZ = MV.velZ;
        }

        PUMP.commitTick(substeps);
        lastFreshRate = RING.freshRate(System.nanoTime(), 1_000_000_000L);

        writeBack(player);
        return true;
    }

    /** Adopt the player's current Minecraft state into the simulation. */
    private static void seedFrom(LocalPlayer player) {
        Vec3 pos = player.position();
        MV.originX = Units.hu(pos.x);
        MV.originY = Units.mcZToEngineY(pos.z);
        MV.originZ = Units.hu(pos.y);

        Vec3 vel = player.getDeltaMovement();
        MV.velX = Units.velToSource(vel.x);
        MV.velY = -Units.velToSource(vel.z);
        MV.velZ = Units.velToSource(vel.y);

        MV.oldButtons = 0;
        SIM.reset(player.onGround());
        PUMP.reset(System.nanoTime());
    }

    /** Copy the simulation's answer onto the entity. The engine's origin is authoritative. */
    private static void writeBack(LocalPlayer player) {
        player.setPos(Units.blocks(MV.originX), Units.blocks(MV.originZ), Units.engineYToMcZ(MV.originY));

        // Vanilla reads deltaMovement for animation, fall damage and the network position delta.
        player.setDeltaMovement(
                Units.velToMc(MV.velX),
                Units.velToMc(MV.velZ),
                -Units.velToMc(MV.velY));

        player.setOnGround(SIM.onGround());
    }

    // ---- control + readouts ----

    public static void setActive(boolean on) {
        if (active == on) return;
        active = on;
        seeded = false;
        if (!on) RING.clear();

        // getEyeHeight() reads a cached field written only by refreshDimensions(), so the eye-height
        // mixin does nothing until this runs — in both directions, 1.62 vanilla and 1.4927 TF2.
        LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p != null) p.refreshDimensions();
    }

    public static boolean isActive() {
        return active;
    }

    /** Re-seed on the next tick — spawn, teleport, dimension change. */
    public static void invalidate() {
        seeded = false;
    }

    /** Add an impulse in hu/s. Velocity only — the ground flag belongs to categorizePosition. */
    public static void addImpulse(float x, float y, float z) {
        SIM.applyAbsVelocityImpulse(MV, x, y, z);
    }

    // ---- the rocket launcher ----

    private static final com.soldiermc.source.TFWeaponGun WEAPON =
            new com.soldiermc.source.TFWeaponGun(
                    com.soldiermc.source.GunStats.ROCKET_LAUNCHER,
                    com.soldiermc.source.TfAmmo.PRIMARY);
    private static final com.soldiermc.source.PlayerAmmo AMMO = new com.soldiermc.source.PlayerAmmo();
    private static final com.soldiermc.source.FireSetup FIRE = new com.soldiermc.source.FireSetup();
    private static final com.soldiermc.source.SelfBlast BLAST = new com.soldiermc.source.SelfBlast();
    private static final java.util.List<com.soldiermc.source.RocketFlight> ROCKETS =
            new java.util.ArrayList<>();

    /** The local player's own id, so his rocket cannot hit him. -1 until the first tick. */
    private static int shooterId = -1;

    private static float lastBlastDamage;
    private static float lastBlastDistance;
    private static float lastBlastForce;
    private static int lastHealthLost;

    static {
        AMMO.setMax(com.soldiermc.source.TfAmmo.PRIMARY, 20);
        AMMO.set(com.soldiermc.source.TfAmmo.PRIMARY, 20);
    }

    /**
     * Runs the {@code FireRocket} setup and puts a rocket in the air. The rocket is simulated
     * entirely inside the ported engine; Minecraft is not asked to move it.
     */
    private static final com.soldiermc.source.ProjectileSink SINK =
            new com.soldiermc.source.ProjectileSink() {
                @Override
                public void fireRocket(float curtime, com.soldiermc.source.MoveData mv,
                                       boolean ducked, boolean critical) {
                    var spawn = new com.soldiermc.source.RocketSpawn();
                    // TF2 cannot look past 89 degrees (cl_pitchdown is FCVAR_CHEAT); Minecraft
                    // clamps to 90, so the port clamps here.
                    float pitch = Math.max(-SourceFeel.PITCH_CLAMP,
                            Math.min(SourceFeel.PITCH_CLAMP, mv.viewPitch));
                    FIRE.fireRocket(WORLD, mv.originX, mv.originY, mv.originZ,
                            pitch, mv.viewYaw, ducked, shooterId, spawn);
                    var r = new com.soldiermc.source.RocketFlight();
                    r.launch(spawn, shooterId,
                            com.soldiermc.weapon.WeaponStatsLoader.rocketLauncher().damage(), critical);
                    ROCKETS.add(r);
                }

                @Override
                public void weaponSound(int sound) {
                    SoldierSounds.weapon(sound);
                }
            };

    /** Advance every live rocket one substep and apply any blast it produces. */
    private static void stepRockets() {
        for (int k = ROCKETS.size() - 1; k >= 0; k--) {
            var r = ROCKETS.get(k);
            boolean detonated = r.step(WORLD, SourceFeel.DT);

            if (!detonated) {
                var lvl = net.minecraft.client.Minecraft.getInstance().level;
                if (lvl != null) SoldierParticles.trail(r.x, r.y, r.z, lvl.getGameTime());
                // TF2 has no lifetime and no range limit; rockets are removed by leaving the world.
                // Leaving the loaded world is the port's substitute, not a TF2 value.
                if (WORLD.outsideLoadedWorld(r.x, r.y, r.z)) ROCKETS.remove(k);
                continue;
            }

            ROCKETS.remove(k);
            boolean direct = r.hitEntityId == shooterId && shooterId >= 0;
            if (BLAST.compute(r.blastX, r.blastY, r.blastZ,
                    MV.originX, MV.originY, MV.originZ,
                    SIM.ducked(), SIM.onGround(), false, direct, r.damage)) {
                SIM.applyAbsVelocityImpulse(MV, BLAST.forceX, BLAST.forceY, BLAST.forceZ);
                lastBlastDamage = BLAST.damage;
                lastBlastDistance = BLAST.falloffDistance;
                lastBlastForce = BLAST.forceMagnitude();
                lastHealthLost = BLAST.healthLost;
            }
            SoldierSounds.explosion(r.blastX, r.blastY, r.blastZ);
            SoldierParticles.explosion(r.blastX, r.blastY, r.blastZ);
        }
    }

    public static int liveRockets() {
        return ROCKETS.size();
    }

    public static int clip() {
        return WEAPON.clip();
    }

    public static int reserveAmmo() {
        return AMMO.get(com.soldiermc.source.TfAmmo.PRIMARY);
    }

    public static int reloadMode() {
        return WEAPON.reloadMode();
    }

    public static float lastBlastDamage() {
        return lastBlastDamage;
    }

    public static float lastBlastDistance() {
        return lastBlastDistance;
    }

    public static float lastBlastForce() {
        return lastBlastForce;
    }

    public static int lastHealthLost() {
        return lastHealthLost;
    }

    public static float horizontalSpeed() {
        return MV.speed2D();
    }

    public static float verticalSpeed() {
        return MV.velZ;
    }

    /**
     * The highest velZ seen inside a substep since the last {@link #clearPeakVelZ()}. Invisible from
     * a per-tick sample, where the remaining substeps have already applied gravity.
     */
    public static float peakVelZ() {
        return peakVelZ;
    }

    public static void clearPeakVelZ() {
        peakVelZ = 0f;
    }

    public static boolean onGround() {
        return SIM.onGround();
    }

    public static boolean ducked() {
        return SIM.ducked();
    }

    /** Engine-space velocity components, hu/s. */
    public static float velX() {
        return MV.velX;
    }

    public static float velY() {
        return MV.velY;
    }

    public static float surfaceFriction() {
        return SIM.surfaceFriction();
    }

    public static int lastSubstepCount() {
        return PUMP.lastSubstepCount();
    }

    public static int lastDropped() {
        return PUMP.lastDropped();
    }

    /** Fresh input samples in the last second — the live R that sets the air-strafe gain rate. */
    public static int freshInputRate() {
        return lastFreshRate;
    }
}
