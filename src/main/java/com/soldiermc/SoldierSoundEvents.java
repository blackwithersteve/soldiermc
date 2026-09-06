package com.soldiermc;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

/**
 * The Soldier's sound events; the audio itself is served by a resource pack built at runtime from
 * the extraction staging directory, so nothing extracted from TF2 ships here. Names and files from
 * {@code scripts/game_sounds_weapons.txt}: {@code Weapon_RPG.Single} is
 * {@code weapons/rocket_shoot.wav}, {@code Weapon_RPG.Reload} is {@code weapons/rocket_reload.wav},
 * {@code BaseExplosionEffect.Sound} is a random pick of {@code explode1/2/3}.
 */
public final class SoldierSoundEvents {

    public static SoundEvent ROCKET_SHOOT;
    public static SoundEvent ROCKET_SHOOT_CRIT;
    public static SoundEvent ROCKET_RELOAD;
    public static SoundEvent EXPLODE;
    public static SoundEvent EMPTY;

    private SoldierSoundEvents() {
    }

    public static void register() {
        ROCKET_SHOOT = register("weapons.rocket_shoot");
        ROCKET_SHOOT_CRIT = register("weapons.rocket_shoot_crit");
        ROCKET_RELOAD = register("weapons.rocket_reload");
        // One event, three files — Minecraft picks the variant, like the three rndwave entries.
        EXPLODE = register("weapons.explode");
        EMPTY = register("weapons.empty");
    }

    private static SoundEvent register(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, path);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }
}
