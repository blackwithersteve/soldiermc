package com.soldiermc.client;

import com.soldiermc.bridge.Units;
import com.soldiermc.source.ProjectileSink;
import net.minecraft.client.Minecraft;
import com.soldiermc.SoldierSoundEvents;
import net.minecraft.sounds.SoundSource;

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
