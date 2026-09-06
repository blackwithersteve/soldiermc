package com.soldiermc.client;

import com.soldiermc.bridge.Units;
import com.soldiermc.source.ProjectileSink;
import net.minecraft.client.Minecraft;
import com.soldiermc.SoldierSoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Weapon audio, resolved from {@code game_sounds_weapons.txt}: {@code Weapon_RPG.Single} is
 * {@code weapons/rocket_shoot.wav}, {@code Weapon_RPG.Reload} is {@code weapons/rocket_reload.wav},
 * and {@code BaseExplosionEffect.Sound} picks at random between {@code explode1/2/3}. The files are
 * served by a pack built at runtime from the extraction staging directory, so nothing extracted
 * from TF2 is in the jar; see {@code SoldierResourcePack}.
 */
public final class SoldierSounds {

    private SoldierSounds() {
    }

    public static void weapon(int sound) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        switch (sound) {
            case ProjectileSink.SINGLE -> play(SoldierSoundEvents.ROCKET_SHOOT, 1.0f, 1.0f);
            case ProjectileSink.EMPTY -> play(SoldierSoundEvents.EMPTY, 0.5f, 1.4f);
            case ProjectileSink.RELOAD -> play(SoldierSoundEvents.ROCKET_RELOAD, 1.0f, 1.0f);
            default -> {
            }
        }
    }

    /** The reload clunk, fired from the animation event rather than from the state change. */
    public static void reloadClunk() {
        play(SoldierSoundEvents.ROCKET_RELOAD, 1.0f, 1.0f);
    }

    public static void explosion(float huX, float huY, float huZ) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        mc.level.playLocalSound(
                Units.blocks(huX), Units.blocks(huZ), Units.engineYToMcZ(huY),
                SoldierSoundEvents.EXPLODE, SoundSource.PLAYERS,
                1.0f, 1.0f, false);
    }

    private static void play(net.minecraft.sounds.SoundEvent event, float volume, float pitch) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                event, SoundSource.PLAYERS, volume, pitch, false);
    }
}
