package com.soldiermc.source;

/**
 * The reserve ammo pool, keyed by ammo type. Player state, not weapon state: the Soldier's shotgun
 * draws on {@link TfAmmo#SECONDARY} (32 rounds), not the rocket pool (20).
 */
public final class PlayerAmmo {

    private final int[] counts = new int[TfAmmo.COUNT];
    private final int[] maxima = new int[TfAmmo.COUNT];

    public void setMax(int ammoType, int max) {
        maxima[ammoType] = max;
    }

    public int max(int ammoType) {
        return maxima[ammoType];
    }

    public int get(int ammoType) {
        return counts[ammoType];
    }

    public void set(int ammoType, int amount) {
        counts[ammoType] = clamp(amount, ammoType);
    }

    /** @return the amount actually removed, less than asked when the pool runs dry */
    public int remove(int ammoType, int amount) {
        int taken = Math.min(amount, counts[ammoType]);
        counts[ammoType] -= taken;
        return taken;
    }

    public void give(int ammoType, int amount) {
        counts[ammoType] = clamp(counts[ammoType] + amount, ammoType);
    }

    private int clamp(int v, int ammoType) {
        if (v < 0) return 0;
        int m = maxima[ammoType];
        return m > 0 && v > m ? m : v;
    }
}
