package com.soldiermc.source;

/** Reload timings come from c_soldier_animations.mdl, not the v_ viewmodel. */
public record GunStats(
        int clipSize,
        int ammoPerShot,
        float attackInterval,
        boolean reloadsSingly,
        float reloadStartSeconds,
        float reloadLoopSeconds,
        float reloadFinishSeconds,
        /** Frame N of a 25 frame loop is cycle N/24, since cycles span frames 0 to 24. */
        float incrementAmmoCycle,
        float reloadSoundCycle,
        float idleAfterFireSeconds,
        float idleSeconds,
        /** 0.5, not dh_draw's 0.8: CTFWeaponBase::Deploy overwrites it. */
        float deploySeconds) {

    public float incrementAmmoDelay() {
        return incrementAmmoCycle * reloadLoopSeconds;
    }

    public float reloadSoundDelay() {
        return reloadSoundCycle * reloadLoopSeconds;
    }

    public static final GunStats ROCKET_LAUNCHER = new GunStats(
            4, 1, 0.8f, true,
            0.500000f, 0.800000f, 0.766667f, 10.0f / 24.0f, 2.0f / 24.0f,
            1.000000f, 1.333333f, 0.500000f);
}
