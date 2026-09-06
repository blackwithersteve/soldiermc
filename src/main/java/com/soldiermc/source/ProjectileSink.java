package com.soldiermc.source;

/**
 * The one way out of the weapon machine. The engine never learns what a rocket is: it says "fire one
 * now, from this state" and the bridge decides whether that becomes a Minecraft entity, a headless
 * test recording, or nothing.
 */
public interface ProjectileSink {

    /** Sound ids, {@code basecombatweapon_shared.h} {@code WeaponSound_t}. */
    int SINGLE = 0;
    int EMPTY = 1;
    int RELOAD = 2;

    /**
     * @param curtime  the substep's curtime, so the projectile can be stamped with its own spawn
     * @param critical the crit die, already rolled
     */
    void fireRocket(float curtime, MoveData mv, boolean ducked, boolean critical);

    void weaponSound(int sound);

    /** A sink that does nothing, for tests that only care about timing. */
    ProjectileSink NULL = new ProjectileSink() {
        @Override
        public void fireRocket(float curtime, MoveData mv, boolean ducked, boolean critical) {
        }

        @Override
        public void weaponSound(int sound) {
        }
    };
}
