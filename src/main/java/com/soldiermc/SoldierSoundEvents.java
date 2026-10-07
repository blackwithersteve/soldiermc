package com.soldiermc;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

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
        EXPLODE = register("weapons.explode");
        EMPTY = register("weapons.empty");
    }

    private static SoundEvent register(String path) {
        Identifier id = Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, path);
        return Registry.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }
}
