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

/** The engine owns position and velocity; the Minecraft player only mirrors them. */
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

    public static boolean consumeJumpEdge() {
        if (!jumped) return false;
        jumped = false;
        return true;
    }

    private SoldierMovement() {
    }

    /** Per frame, after the mouse flush; per tick would cap air-strafe gain at 20 Hz. */
    public static void sampleFrame(LocalPlayer player) {
        if (player == null) return;

        Input keys = player.input.keyPresses;

        float forward = 0f;
        if (keys.forward()) forward += SourceFeel.CL_FORWARDSPEED;
        if (keys.backward()) forward -= SourceFeel.CL_BACKSPEED;

        // sideMove is positive to the right, though Source's +Y axis points left.
        float side = 0f;
        if (keys.left()) side -= SourceFeel.CL_SIDESPEED;
        if (keys.right()) side += SourceFeel.CL_SIDESPEED;

        int buttons = 0;
        if (keys.jump()) buttons |= Buttons.IN_JUMP;
        if (keys.shift()) buttons |= Buttons.IN_DUCK;

        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.options.keyAttack.isDown()) buttons |= Buttons.IN_ATTACK;
        if (mc.options.keyUse.isDown()) buttons |= Buttons.IN_ATTACK2;
        if (SoldierClient.reloadDown()) buttons |= Buttons.IN_RELOAD;

        RING.sample(System.nanoTime(),
                player.getXRot(),
                Units.sourceYaw(player.getYRot()),
                forward, side, buttons);
    }

    /** Returns true when the engine moved the player and vanilla travel must not run. */
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
            if (wasGrounded && !SIM.onGround() && MV.velZ > 0f) jumped = true;

            // Rockets step after movement, so a blast reads this substep's ground flag.
            stepRockets();
            WEAPON.runCommand(t, MV.buttons, AMMO, SINK, MV, SIM.ducked());

            if (MV.velZ > peakVelZ) peakVelZ = MV.velZ;
        }

        PUMP.commitTick(substeps);
        lastFreshRate = RING.freshRate(System.nanoTime(), 1_000_000_000L);

        writeBack(player);
        return true;
    }

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
        PUMP.reset();
    }

    private static void writeBack(LocalPlayer player) {
        player.setPos(Units.blocks(MV.originX), Units.blocks(MV.originZ), Units.engineYToMcZ(MV.originY));

        // Vanilla reads deltaMovement for animation, fall damage and network deltas.
        player.setDeltaMovement(
                Units.velToMc(MV.velX),
                Units.velToMc(MV.velZ),
                -Units.velToMc(MV.velY));

        player.setOnGround(SIM.onGround());
    }

    public static void setActive(boolean on) {
        if (active == on) return;
        active = on;
        seeded = false;
        if (!on) RING.clear();

        // Eye height is cached until refreshDimensions, so the eye-height mixin needs this.
        LocalPlayer p = net.minecraft.client.Minecraft.getInstance().player;
        if (p != null) p.refreshDimensions();
    }

    public static boolean isActive() {
        return active;
    }

    /** Impulse in hu/s; velocity only, the ground flag is left to categorizePosition. */
    public static void addImpulse(float x, float y, float z) {
        SIM.applyAbsVelocityImpulse(MV, x, y, z);
    }

    private static final com.soldiermc.source.TFWeaponGun WEAPON =
            new com.soldiermc.source.TFWeaponGun(
                    com.soldiermc.source.GunStats.ROCKET_LAUNCHER,
                    com.soldiermc.source.TfAmmo.PRIMARY);
    private static final com.soldiermc.source.PlayerAmmo AMMO = new com.soldiermc.source.PlayerAmmo();
    private static final com.soldiermc.source.FireSetup FIRE = new com.soldiermc.source.FireSetup();
    private static final com.soldiermc.source.SelfBlast BLAST = new com.soldiermc.source.SelfBlast();
    private static final java.util.List<com.soldiermc.source.RocketFlight> ROCKETS =
            new java.util.ArrayList<>();

    private static int shooterId = -1;

    private static float lastBlastDamage;
    private static float lastBlastDistance;
    private static float lastBlastForce;
    private static int lastHealthLost;

    static {
        AMMO.setMax(com.soldiermc.source.TfAmmo.PRIMARY, 20);
        AMMO.set(com.soldiermc.source.TfAmmo.PRIMARY, 20);
    }

    private static final com.soldiermc.source.ProjectileSink SINK =
            new com.soldiermc.source.ProjectileSink() {
                @Override
                public void fireRocket(float curtime, com.soldiermc.source.MoveData mv,
                                       boolean ducked, boolean critical) {
                    var spawn = new com.soldiermc.source.RocketSpawn();
                    // TF2 pitch stops at 89 degrees; Minecraft allows 90.
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

    private static void stepRockets() {
        for (int k = ROCKETS.size() - 1; k >= 0; k--) {
            var r = ROCKETS.get(k);
            boolean detonated = r.step(WORLD, SourceFeel.DT);

            if (!detonated) {
                var lvl = net.minecraft.client.Minecraft.getInstance().level;
                if (lvl != null) SoldierParticles.trail(r.x, r.y, r.z, lvl.getGameTime());
                // TF2 rockets never expire; leaving the loaded world stands in for the map edge.
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

    public static int freshInputRate() {
        return lastFreshRate;
    }
}
