package com.soldiermc.client;

import com.soldiermc.bridge.Units;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;

/**
 * The rocket's trail and explosion, approximated with Minecraft particles rather than ported: TF2's
 * own {@code ExplosionCore_wall} and {@code rockettrail} systems live in {@code particles/*.pcf},
 * a binary format this project has no reader for.
 */
public final class SoldierParticles {

    /**
     * The trail is emitted per Minecraft tick, not per substep. Rockets step 3-4 times a tick at
     * 66.67 Hz, so per-substep emission would tie trail density to the substep schedule.
     */
    private static long lastTrailTick = -1;

    private SoldierParticles() {
    }

    public static void trail(float huX, float huY, float huZ, long gameTime) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        if (gameTime == lastTrailTick) return;
        lastTrailTick = gameTime;

        double x = Units.blocks(huX);
        double y = Units.blocks(huZ);
        double z = Units.engineYToMcZ(huY);
        mc.level.addParticle(ParticleTypes.SMOKE, x, y, z, 0.0, 0.0, 0.0);
        mc.level.addParticle(ParticleTypes.FLAME, x, y, z, 0.0, 0.0, 0.0);
    }

    public static void explosion(float huX, float huY, float huZ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;

        double x = Units.blocks(huX);
        double y = Units.blocks(huZ);
        double z = Units.engineYToMcZ(huY);

        mc.level.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 0.0, 0.0, 0.0);

        // A ring of smoke thrown outward, so the blast reads as having a radius. Fixed angles, so
        // two identical rocket jumps look identical.
        final int n = 12;
        for (int i = 0; i < n; i++) {
            double a = (Math.PI * 2.0 / n) * i;
            double dx = Math.cos(a) * 0.35;
            double dz = Math.sin(a) * 0.35;
            mc.level.addParticle(ParticleTypes.LARGE_SMOKE, x, y + 0.1, z, dx, 0.08, dz);
        }
    }
}
