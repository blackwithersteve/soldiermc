package com.soldiermc.source;

/**
 * {@code CTFWeaponBaseGun::PrimaryAttack} and {@code CTFWeaponBase}'s singly-reload machine, in
 * pure Java, one call per substep at 66.67 Hz with no allocation. {@code CBasePlayer::ItemPostFrame}
 * (baseplayer_shared.cpp:268-272) is one call site picking {@code ItemBusyFrame} or
 * {@code ItemPostFrame} on {@code curtime < m_flNextAttack}, and {@code ReloadSingly} advances one
 * state per usercmd, so pumping it twice collapses the one-substep gap at
 * {@code TF_RELOADING_CONTINUE}. Not ported: {@code CBaseCombatWeapon::CheckReload}
 * (basecombatweapon_shared.cpp:2140-2191), a second singly-reload path that also does
 * {@code m_iClip1 += 1}, dead here because {@code m_bInReload} is never set on the singly path.
 */
public final class TFWeaponGun {

    /** {@code tf_weaponbase.h:63-66}. */
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
    /** {@code CBasePlayer::m_flNextAttack} — player-scoped, and the busy/post selector. */
    private float playerNextAttack;

    private boolean reloadedThroughAnimEvent;
    private int reloadStartClipAmount;

    /** When {@code AE_WPN_INCREMENTAMMO} fires, or +inf when no loop is running. */
    private float pendingAmmoEventAt = Float.POSITIVE_INFINITY;

    /** When the reload clunk plays. A different event, a quarter second earlier. */
    private float pendingSoundAt = Float.POSITIVE_INFINITY;

    /** {@code m_bInAttack2}, tf_weaponbase.cpp:2388-2395 — latches while M2 is held. */
    private boolean inAttack2;

    private int oldButtons;

    /** Observable for tests and the instrument panel. */
    private int shotsFired;
    private int ammoEvents;

    public TFWeaponGun(GunStats stats, int ammoType) {
        this.stats = stats;
        this.ammoType = ammoType;
        this.clip1 = stats.clipSize();
    }

    // ---- entry point ----

    /**
     * One usercmd, once per substep.
     *
     * @param t the substep's curtime, from {@code SourcePump.curtime(i)}
     */
    public void runCommand(float t, int buttons, PlayerAmmo ammo, ProjectileSink sink,
                           MoveData mv, boolean ducked) {

        // Animation events run on the model's clock, not the weapon's frame gates, so they are
        // checked before either branch: cancelling a reload in the 0.467 s after the clunk keeps
        // the rocket.
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

        // tf_weaponbase.cpp:2393-2395 — the latch clears on release, not on a timer.
        if ((buttons & Buttons.IN_ATTACK2) == 0) inAttack2 = false;

        if (t < playerNextAttack) {
            itemBusyFrame(t, buttons, ammo);
        } else {
            itemPostFrame(t, buttons, ammo, sink, mv, ducked);
        }

        oldButtons = buttons;
    }

    /**
     * {@code CTFWeaponBase::ItemBusyFrame}. The reload machine keeps running while the player is
     * gated, so a reload survives a weapon switch's deploy window.
     */
    private void itemBusyFrame(float t, int buttons, PlayerAmmo ammo) {
        if (stats.reloadsSingly() && reloadMode != TF_RELOAD_START) {
            reloadSingly(t, ammo);
        }
    }

    /**
     * {@code CBaseCombatWeapon::ItemPostFrame}, basecombatweapon_shared.cpp:1700-1830, with TF2's
     * singly-reload override folded in at the top ({@code tf_weaponbase.cpp:2659}).
     */
    private void itemPostFrame(float t, int buttons, PlayerAmmo ammo, ProjectileSink sink,
                               MoveData mv, boolean ducked) {

        if (stats.reloadsSingly()) {
            reloadSinglyPostFrame(t, ammo);
        }

        // Secondary has priority, and ShouldBlockPrimaryFire() is a constant true for the RL
        // (tf_weapon_rocketlauncher.cpp:283 -> !AutoFiresFullClip()), so holding M2 sets bFired and
        // skips primary for that command.
        boolean fired = false;
        if ((buttons & Buttons.IN_ATTACK2) != 0 && nextSecondaryAttack <= t) {
            fired = true;                       // ShouldBlockPrimaryFire(), a constant true here
            // CTFWeaponBaseGun::SecondaryAttack returns on m_bInAttack2 BEFORE stamping the timer
            // (gun:1-4), so only the first press stamps +0.5 and the gate stays open while M2 is
            // held.
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

        // bcw:1803. No idle gate on this path, so a held +reload starts as soon as
        // nextPrimaryAttack expires rather than waiting for the idle stamp.
        if ((buttons & Buttons.IN_RELOAD) != 0 && !isReloading()) {
            reload(t, ammo);
        }

        // bcw:1813-1820. ReloadOrSwitchWeapons auto-starts a reload on an empty clip only
        // (:1396-1405, strict < on both attack times); a partial clip falls through to WeaponIdle,
        // whose stamp blocks ReloadSinglyPostFrame's idle gate for 1.333 s at a time.
        boolean anyHeld = (buttons & (Buttons.IN_ATTACK | Buttons.IN_ATTACK2 | Buttons.IN_RELOAD)) != 0;
        if (!anyHeld) {
            if (!reloadOrSwitchWeapons(t, ammo)) {
                weaponIdle(t);
            }
        }
    }

    // ---- firing ----

    /**
     * {@code CTFWeaponBaseGun::PrimaryAttack}, tf_weaponbase_gun.cpp:80-210. The ammo check comes
     * before the timing check, and the timing check is a strict {@code >} so a shot lands on the
     * substep where {@code curtime == nextPrimaryAttack}. {@code UpdatePunchAngles} is a no-op here
     * — the RL's {@code PunchAngle} is 0.0 and fails the {@code > 0} guard at gun:482 — so the
     * launcher takes no shared-RNG draw.
     */
    private void primaryAttack(float t, PlayerAmmo ammo, ProjectileSink sink,
                               MoveData mv, boolean ducked) {
        if (clip1 <= 0) return;                       // gun:87  ammo BEFORE timing
        if (nextPrimaryAttack > t) return;            // gun:91  strict >, equality fires

        // gun:133 CalcIsAttackCritical. Stubbed false: crits need the shared RNG stream, which
        // nothing else in the port draws from yet.
        boolean critical = false;

        sink.weaponSound(ProjectileSink.SINGLE);
        sink.fireRocket(t, mv, ducked, critical);     // gun:162
        shotsFired++;

        clip1 -= stats.ammoPerShot();                 // gun:352 -> :381
        nextPrimaryAttack = t + stats.attackInterval();   // gun:184 assigned from curtime, never
                                                          //         accumulated
        timeWeaponIdle = t + stats.idleAfterFireSeconds();  // gun:192-199, both branches identical
        reloadMode = TF_RELOAD_START;                       // gun:204
    }

    /**
     * {@code CBaseCombatWeapon::HandleFireOnEmpty}. Two visits: the first stamps the empty sound and
     * pushes the timer, the second starts the reload, one substep later.
     */
    private void handleFireOnEmpty(float t, ProjectileSink sink) {
        sink.weaponSound(ProjectileSink.EMPTY);
        nextPrimaryAttack = t + SourceFeel.EMPTY_SOUND_INTERVAL;
    }

    // ---- reload machine ----

    private boolean isReloading() {
        return reloadMode != TF_RELOAD_START;
    }

    /** {@code CTFWeaponBase::Reload} — the singly path only ever sets the mode. */
    private void reload(float t, PlayerAmmo ammo) {
        if (clip1 >= stats.clipSize()) return;
        if (ammo.get(ammoType) <= 0) return;
        if (nextPrimaryAttack > t) return;
        // Enters at TF_RELOAD_START so the 0.5 s dh_reload_start is stamped; entering at
        // TF_RELOADING skips it and shifts the table 21 substeps early.
        reloadSingly(t, ammo);
    }

    /** {@code CTFWeaponBase::ReloadSinglyPostFrame}, tf_weaponbase.cpp:2659 — idle-gated. */
    private void reloadSinglyPostFrame(float t, PlayerAmmo ammo) {
        if (reloadMode == TF_RELOAD_START) return;
        if (timeWeaponIdle > t) return;
        reloadSingly(t, ammo);
    }

    /**
     * {@code CTFWeaponBase::ReloadSingly}, tf_weaponbase.cpp:1935-2095. One case per call.
     * {@code TF_RELOADING_CONTINUE} sets no timer, which creates the one-substep gap before the next
     * {@code TF_RELOADING}: 0.825 s per rocket rather than 0.810.
     */
    private void reloadSingly(float t, PlayerAmmo ammo) {
        switch (reloadMode) {
            case TF_RELOAD_START -> {
                setReloadTimer(t, stats.reloadStartSeconds());
                reloadStartClipAmount = clip1;
                reloadMode = TF_RELOADING;
            }
            case TF_RELOADING -> {
                if (timeWeaponIdle > t) return;                 // idle gate
                reloadedThroughAnimEvent = false;
                reloadStartClipAmount = clip1;
                setReloadTimer(t, stats.reloadLoopSeconds());
                pendingAmmoEventAt = t + stats.incrementAmmoDelay();
                pendingSoundAt = t + stats.reloadSoundDelay();
                reloadMode = TF_RELOADING_CONTINUE;
            }
            case TF_RELOADING_CONTINUE -> {
                if (timeWeaponIdle > t) return;                 // idle gate
                incrementAmmo(ammo);                            // no-op once the event fired
                reloadMode = (clip1 >= stats.clipSize() || ammo.get(ammoType) <= 0)
                        ? TF_RELOAD_FINISH
                        : TF_RELOADING;
                // No timer — see the javadoc.
            }
            case TF_RELOAD_FINISH -> {
                // :2083-2086 — SetReloadTimer is commented out in Valve's source; idle time only.
                timeWeaponIdle = t + stats.reloadFinishSeconds();
                reloadMode = TF_RELOAD_START;
            }
            default -> {
            }
        }
    }

    /** {@code CTFWeaponBase::SetReloadTimer} — stamps BOTH the idle and the player's next attack. */
    private void setReloadTimer(float t, float duration) {
        timeWeaponIdle = t + duration;
        playerNextAttack = t + duration;
    }

    /**
     * {@code CTFWeaponBase::IncrementAmmo}. A no-op once {@code m_bReloadedThroughAnimEvent} is set
     * (:2102), so the same rocket is not added twice. {@code CheckReloadMisfire} (:2108) is a
     * constant false for the stock launcher — {@code CanOverload()} is false — and is transcribed as
     * that literal.
     */
    private void incrementAmmo(PlayerAmmo ammo) {
        if (reloadedThroughAnimEvent) return;
        if (clip1 >= stats.clipSize()) return;
        int taken = ammo.remove(ammoType, 1);
        if (taken > 0) clip1 += taken;
    }

    /**
     * {@code CBaseCombatWeapon::ReloadOrSwitchWeapons}, bcw:1396-1405. Strict {@code <} on both
     * attack times, and only on a completely empty clip. {@code ITEM_FLAG_NOAUTORELOAD} keeps its
     * default of 0 here: the key is absent from the script and weapon_parse.cpp:392 reads an absent
     * key as -1, which takes neither branch.
     */
    private boolean reloadOrSwitchWeapons(float t, PlayerAmmo ammo) {
        if (clip1 != 0) return false;
        if (!(nextPrimaryAttack < t) || !(nextSecondaryAttack < t)) return false;
        if (ammo.get(ammoType) <= 0) return false;
        if (reloadMode != TF_RELOAD_START) return false;
        reloadSingly(t, ammo);
        return true;
    }

    /**
     * {@code CTFWeaponBase::WeaponIdle}, tf_weaponbase.cpp:2781-2787. The guard is transcribed as
     * well as the call: a singly-reloading weapon mid-reload stamping the idle time would block its
     * own reload machine.
     */
    private void weaponIdle(float t) {
        if (stats.reloadsSingly() && reloadMode != TF_RELOAD_START) return;
        if (timeWeaponIdle > t) return;
        timeWeaponIdle = t + stats.idleSeconds();
    }

    // ---- deploy / holster ----

    /**
     * {@code CTFWeaponBase::Deploy}, tf_weaponbase.cpp:1229-1325. {@code DefaultDeploy}'s
     * {@code curtime + 0.8} from {@code dh_draw} is overwritten at :1314, so deploy is 0.5 s.
     */
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

    /**
     * {@code CTFWeaponBase::Holster}. No {@code ACT_PRIMARY_VM_HOLSTER} on this model, so the
     * sequence duration is 0, but bcw:1508-1512 still runs {@code SetNextAttack(curtime + 0)},
     * cancelling a running reload's gate.
     */
    public void holster(float t) {
        reloadMode = TF_RELOAD_START;
        playerNextAttack = t;
        pendingAmmoEventAt = Float.POSITIVE_INFINITY;
        pendingSoundAt = Float.POSITIVE_INFINITY;
    }

    /**
     * The {@code travel} gate bail-out: the simulation pauses, so the weapon resets to a clean idle
     * rather than sitting mid-reload with a stale curtime. Diverges from Source.
     */
    public void suspend(float t) {
        reloadMode = TF_RELOAD_START;
        nextPrimaryAttack = t;
        playerNextAttack = t;
        pendingAmmoEventAt = Float.POSITIVE_INFINITY;
        reloadedThroughAnimEvent = false;
    }

    // ---- readouts ----

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
