package com.soldiermc.source;

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

    public int remove(int ammoType, int amount) {
        int taken = Math.min(amount, counts[ammoType]);
        counts[ammoType] -= taken;
        return taken;
    }

    private int clamp(int v, int ammoType) {
        if (v < 0) return 0;
        int m = maxima[ammoType];
        return m > 0 && v > m ? m : v;
    }
}
