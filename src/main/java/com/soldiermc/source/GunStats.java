package com.soldiermc.source;

/**
 * The numbers {@link TFWeaponGun} runs on. This package imports {@code java.*} only, so it cannot
 * see {@code com.soldiermc.weapon.WeaponStats}; the bridge maps one onto the other. The animation
 * durations are measured from the shipped model, not the weapon script: {@code ReloadSingly} calls
 * {@code SequenceDuration()} and the launcher is {@code attach_to_hands 1}, so the chain is
 * {@code c_soldier_arms.mdl} → {@code $includemodel c_soldier_animations.mdl}, not the viewmodel
 * {@code v_rocketlauncher_soldier.mdl} with its 26-frame (0.833333 s) reload loop.
 */
public record GunStats(
        int clipSize,
        int ammoPerShot,
        /** {@code TimeFireDelay}, weapon script. */
        float attackInterval,
        boolean reloadsSingly,
        /** {@code @dh_reload_start}, 16 frames @ 30 fps. */
        float reloadStartSeconds,
        /** {@code @dh_reload_loop}, 25 frames @ 30 fps. */
        float reloadLoopSeconds,
        /** {@code @dh_reload_finish}, 24 frames @ 30 fps. */
        float reloadFinishSeconds,
        /**
         * Cycle of {@code AE_WPN_INCREMENTAMMO} inside the reload loop — frame 10 of 25, stored as
         * {@code 10.0/24.0} rather than the rounded 0.4167. The rocket enters the clip 0.333333 s
         * into a 0.800000 s loop, leaving 0.467 s in which cancelling the reload still keeps it.
         */
        float incrementAmmoCycle,
        /**
         * Cycle of the {@code AE_CL_PLAYSOUND 'Weapon_RPG.Reload'} event in the same loop — frame 2
         * of 25, {@code 2.0/24.0}. A separate event from the ammo one, a quarter second earlier.
         */
        float reloadSoundCycle,
        /** {@code SetIdealActivity} stamp after firing, basecombatweapon_shared.cpp:2425. */
        float idleAfterFireSeconds,
        /** {@code @dh_idle}. Blocks {@code ReloadSinglyPostFrame}'s idle gate. */
        float idleSeconds,
        /** {@code CTFWeaponBase::Deploy} overwrites DefaultDeploy's 0.8 at tf_weaponbase.cpp:1314. */
        float deploySeconds) {

    /** Seconds from the start of the reload loop to the rocket landing in the clip. */
    public float incrementAmmoDelay() {
        return incrementAmmoCycle * reloadLoopSeconds;
    }

    /** Seconds from the start of the reload loop to the audible clunk. */
    public float reloadSoundDelay() {
        return reloadSoundCycle * reloadLoopSeconds;
    }

    /**
     * Stock Rocket Launcher. Timings measured from {@code c_soldier_animations.mdl}; clip, interval
     * and ammo-per-shot ICE-decoded from {@code tf_weapon_rocketlauncher.ctx}. {@code ammoPerShot}
     * is 1 because the key is absent from the script and {@code tf_weapon_parse.cpp:79} defaults it.
     */
    public static final GunStats ROCKET_LAUNCHER = new GunStats(
            4, 1, 0.8f, true,
            0.500000f, 0.800000f, 0.766667f, 10.0f / 24.0f, 2.0f / 24.0f,
            1.000000f, 1.333333f, 0.500000f);
}
