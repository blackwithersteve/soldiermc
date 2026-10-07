package com.soldiermc.source;

/** CTFWeaponBaseGun firing and CTFWeaponBase's singly reload, tf_weaponbase.cpp. */
public final class TFWeaponGun {

    public static final int TF_RELOAD_START = 0;
    public static final int TF_RELOADING = 1;
    public static final int TF_RELOADING_CONTINUE = 2;
    public static final int TF_RELOAD_FINISH = 3;

    private final GunStats stats;
    private final int ammoType;

    private int clip1;
    private int reloadMode = TF_RELOAD_START;

    private float nextPrimaryAttack;
    private float nextSecondaryAttack;
    private float timeWeaponIdle;
    private float playerNextAttack;

    private boolean reloadedThroughAnimEvent;
    private int reloadStartClipAmount;

    private float pendingAmmoEventAt = Float.POSITIVE_INFINITY;
    private float pendingSoundAt = Float.POSITIVE_INFINITY;

    private boolean inAttack2;

    private int oldButtons;

    private int shotsFired;
    private int ammoEvents;

    public TFWeaponGun(GunStats stats, int ammoType) {
        this.stats = stats;
        this.ammoType = ammoType;
        this.clip1 = stats.clipSize();
    }

    public void runCommand(float t, int buttons, PlayerAmmo ammo, ProjectileSink sink,
                           MoveData mv, boolean ducked) {

        // Anim events run on the model's clock, ahead of the busy and post frame gates.
        if (t >= pendingSoundAt) {
            pendingSoundAt = Float.POSITIVE_INFINITY;
            sink.weaponSound(ProjectileSink.RELOAD);
        }

        if (t >= pendingAmmoEventAt) {
            pendingAmmoEventAt = Float.POSITIVE_INFINITY;
            incrementAmmo(ammo);
            reloadedThroughAnimEvent = true;
            ammoEvents++;
        }

        if ((buttons & Buttons.IN_ATTACK2) == 0) inAttack2 = false;

        if (t < playerNextAttack) {
            itemBusyFrame(t, buttons, ammo);
        } else {
            itemPostFrame(t, buttons, ammo, sink, mv, ducked);
        }

        oldButtons = buttons;
    }

    private void itemBusyFrame(float t, int buttons, PlayerAmmo ammo) {
        if (stats.reloadsSingly() && reloadMode != TF_RELOAD_START) {
            reloadSingly(t, ammo);
        }
    }

    private void itemPostFrame(float t, int buttons, PlayerAmmo ammo, ProjectileSink sink,
                               MoveData mv, boolean ducked) {

        if (stats.reloadsSingly()) {
            reloadSinglyPostFrame(t, ammo);
        }

        // ShouldBlockPrimaryFire() is true for the RL, so holding M2 blocks primary fire.
        boolean fired = false;
        if ((buttons & Buttons.IN_ATTACK2) != 0 && nextSecondaryAttack <= t) {
            fired = true;
            if (!inAttack2) {
                inAttack2 = true;
                nextSecondaryAttack = t + SourceFeel.SECONDARY_REFIRE;
            }
        }

        if (!fired && (buttons & Buttons.IN_ATTACK) != 0 && nextPrimaryAttack <= t) {
            if (clip1 <= 0) {
                handleFireOnEmpty(t, sink);
            } else {
                primaryAttack(t, ammo, sink, mv, ducked);
            }
        }

        if ((buttons & Buttons.IN_RELOAD) != 0 && !isReloading()) {
            reload(t, ammo);
        }

        boolean anyHeld = (buttons & (Buttons.IN_ATTACK | Buttons.IN_ATTACK2 | Buttons.IN_RELOAD)) != 0;
        if (!anyHeld) {
            if (!reloadOrSwitchWeapons(t, ammo)) {
                weaponIdle(t);
            }
        }
    }

    private void primaryAttack(float t, PlayerAmmo ammo, ProjectileSink sink,
                               MoveData mv, boolean ducked) {
        if (clip1 <= 0) return;
        if (nextPrimaryAttack > t) return;

        // Crits are stubbed off: they need Source's shared RNG stream.
        boolean critical = false;

        sink.weaponSound(ProjectileSink.SINGLE);
        sink.fireRocket(t, mv, ducked, critical);
        shotsFired++;

        clip1 -= stats.ammoPerShot();
        // From curtime, not accumulated from the last shot, as in Source.
        nextPrimaryAttack = t + stats.attackInterval();
        timeWeaponIdle = t + stats.idleAfterFireSeconds();
        reloadMode = TF_RELOAD_START;
    }

    private void handleFireOnEmpty(float t, ProjectileSink sink) {
        sink.weaponSound(ProjectileSink.EMPTY);
        nextPrimaryAttack = t + SourceFeel.EMPTY_SOUND_INTERVAL;
    }

    private boolean isReloading() {
        return reloadMode != TF_RELOAD_START;
    }

    private void reload(float t, PlayerAmmo ammo) {
        if (clip1 >= stats.clipSize()) return;
        if (ammo.get(ammoType) <= 0) return;
        if (nextPrimaryAttack > t) return;
        reloadSingly(t, ammo);
    }

    private void reloadSinglyPostFrame(float t, PlayerAmmo ammo) {
        if (reloadMode == TF_RELOAD_START) return;
        if (timeWeaponIdle > t) return;
        reloadSingly(t, ammo);
    }

    /** One state per call; CONTINUE sets no timer, so a rocket takes 0.825 s, not 0.810. */
    private void reloadSingly(float t, PlayerAmmo ammo) {
        switch (reloadMode) {
            case TF_RELOAD_START -> {
                setReloadTimer(t, stats.reloadStartSeconds());
                reloadStartClipAmount = clip1;
                reloadMode = TF_RELOADING;
            }
            case TF_RELOADING -> {
                if (timeWeaponIdle > t) return;
                reloadedThroughAnimEvent = false;
                reloadStartClipAmount = clip1;
                setReloadTimer(t, stats.reloadLoopSeconds());
                pendingAmmoEventAt = t + stats.incrementAmmoDelay();
                pendingSoundAt = t + stats.reloadSoundDelay();
                reloadMode = TF_RELOADING_CONTINUE;
            }
            case TF_RELOADING_CONTINUE -> {
                if (timeWeaponIdle > t) return;
                incrementAmmo(ammo);
                reloadMode = (clip1 >= stats.clipSize() || ammo.get(ammoType) <= 0)
                        ? TF_RELOAD_FINISH
                        : TF_RELOADING;
            }
            case TF_RELOAD_FINISH -> {
                // Idle time only: Valve's SetReloadTimer call here is commented out.
                timeWeaponIdle = t + stats.reloadFinishSeconds();
                reloadMode = TF_RELOAD_START;
            }
            default -> {
            }
        }
    }

    private void setReloadTimer(float t, float duration) {
        timeWeaponIdle = t + duration;
        playerNextAttack = t + duration;
    }

    private void incrementAmmo(PlayerAmmo ammo) {
        if (reloadedThroughAnimEvent) return;
        if (clip1 >= stats.clipSize()) return;
        int taken = ammo.remove(ammoType, 1);
        if (taken > 0) clip1 += taken;
    }

    private boolean reloadOrSwitchWeapons(float t, PlayerAmmo ammo) {
        if (clip1 != 0) return false;
        if (!(nextPrimaryAttack < t) || !(nextSecondaryAttack < t)) return false;
        if (ammo.get(ammoType) <= 0) return false;
        if (reloadMode != TF_RELOAD_START) return false;
        reloadSingly(t, ammo);
        return true;
    }

    private void weaponIdle(float t) {
        if (stats.reloadsSingly() && reloadMode != TF_RELOAD_START) return;
        if (timeWeaponIdle > t) return;
        timeWeaponIdle = t + stats.idleSeconds();
    }

    public void deploy(float t) {
        float originalPrimary = nextPrimaryAttack;
        float originalSecondary = nextSecondaryAttack;
        reloadMode = TF_RELOAD_START;
        pendingSoundAt = Float.POSITIVE_INFINITY;
        nextPrimaryAttack = Math.max(originalPrimary, t + stats.deploySeconds());
        nextSecondaryAttack = Math.max(originalSecondary, nextPrimaryAttack);
        playerNextAttack = nextPrimaryAttack;
        pendingAmmoEventAt = Float.POSITIVE_INFINITY;
    }

    public void holster(float t) {
        reloadMode = TF_RELOAD_START;
        playerNextAttack = t;
        pendingAmmoEventAt = Float.POSITIVE_INFINITY;
        pendingSoundAt = Float.POSITIVE_INFINITY;
    }

    public int clip() {
        return clip1;
    }

    public void setClip(int n) {
        clip1 = n;
    }

    public int reloadMode() {
        return reloadMode;
    }

    public float nextPrimaryAttack() {
        return nextPrimaryAttack;
    }

    public float timeWeaponIdle() {
        return timeWeaponIdle;
    }

    public float playerNextAttack() {
        return playerNextAttack;
    }

    public int shotsFired() {
        return shotsFired;
    }

    public int ammoEvents() {
        return ammoEvents;
    }

    public boolean reloadedThroughAnimEvent() {
        return reloadedThroughAnimEvent;
    }

    public int reloadStartClipAmount() {
        return reloadStartClipAmount;
    }
}
