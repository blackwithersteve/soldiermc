package com.soldiermc.weapon;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One weapon's numbers, loaded from data/soldiermc/weapons/&lt;name&gt;.json. Every field is optional
 * with a default, so a partial file overrides only what it names; distances are Hammer units and
 * times seconds - TF2's own units, not blocks and ticks - and conversion happens through SourceFeel.
 */
public record WeaponStats(
    int clipSize,
    int reserveAmmo,
    int ammoPerShot,
    float damage,
    int bulletsPerShot,
    float spread,
    float rangeHu,
    float attackIntervalSeconds,
    float reloadStartSeconds,
    float reloadRepeatSeconds,
    boolean reloadsSingly,
    float reloadAnimStartSeconds,
    float reloadAnimRepeatSeconds,
    float smackDelaySeconds,
    float projectileSpeedHu,
    float blastRadiusHu,
    float selfBlastRadiusHu,
    float damageRampOptimalHu,
    float damageRangeSpread,
    float closeRangeRampScale,
    float blastPushHu,
    float selfDamageScale
) {

    /** Stock Rocket Launcher, so the mod runs correctly with no data file present at all. */
    public static final WeaponStats ROCKET_LAUNCHER_FALLBACK = new WeaponStats(
        4, 20, 1, 90.0f, 1, 0.0f, 0.0f,
        0.8f, 0.1f, 0.83f, true, 0.50f, 0.80f, 0.2f,
        1100.0f, 146.0f, 121.0f,
        512.0f, 0.5f, 0.5f, 400.0f, 1.0f);

    /** Ammo, damage and timing: the 14 keys that come out of the weapon script. */
    private record Script(
        int clipSize, int reserveAmmo, int ammoPerShot, float damage, int bulletsPerShot,
        float spread, float rangeHu, float attackIntervalSeconds, float reloadStartSeconds,
        float reloadRepeatSeconds, boolean reloadsSingly, float reloadAnimStartSeconds,
        float reloadAnimRepeatSeconds, float smackDelaySeconds) {
    }

    /** Ballistics and the damage ramp: the 8 keys that come from hardcoded C++ constants. */
    private record Ballistics(
        float projectileSpeedHu, float blastRadiusHu, float selfBlastRadiusHu,
        float damageRampOptimalHu, float damageRangeSpread, float closeRangeRampScale,
        float blastPushHu, float selfDamageScale) {
    }

    private static final MapCodec<Script> SCRIPT = RecordCodecBuilder.mapCodec(i -> i.group(
        Codec.INT.optionalFieldOf("clip_size", 4).forGetter(Script::clipSize),
        Codec.INT.optionalFieldOf("reserve_ammo", 20).forGetter(Script::reserveAmmo),
        Codec.INT.optionalFieldOf("ammo_per_shot", 1).forGetter(Script::ammoPerShot),
        Codec.FLOAT.optionalFieldOf("damage", 90.0f).forGetter(Script::damage),
        Codec.INT.optionalFieldOf("bullets_per_shot", 1).forGetter(Script::bulletsPerShot),
        Codec.FLOAT.optionalFieldOf("spread", 0.0f).forGetter(Script::spread),
        Codec.FLOAT.optionalFieldOf("range_hu", 0.0f).forGetter(Script::rangeHu),
        Codec.FLOAT.optionalFieldOf("attack_interval_seconds", 0.8f)
            .forGetter(Script::attackIntervalSeconds),
        Codec.FLOAT.optionalFieldOf("reload_start_seconds", 0.1f)
            .forGetter(Script::reloadStartSeconds),
        Codec.FLOAT.optionalFieldOf("reload_repeat_seconds", 0.83f)
            .forGetter(Script::reloadRepeatSeconds),
        Codec.BOOL.optionalFieldOf("reloads_singly", true).forGetter(Script::reloadsSingly),
        Codec.FLOAT.optionalFieldOf("reload_anim_start_seconds", 0.92f)
            .forGetter(Script::reloadAnimStartSeconds),
        Codec.FLOAT.optionalFieldOf("reload_anim_repeat_seconds", 0.80f)
            .forGetter(Script::reloadAnimRepeatSeconds),
        Codec.FLOAT.optionalFieldOf("smack_delay_seconds", 0.2f)
            .forGetter(Script::smackDelaySeconds)
    ).apply(i, Script::new));

    private static final MapCodec<Ballistics> BALLISTICS = RecordCodecBuilder.mapCodec(i -> i.group(
        Codec.FLOAT.optionalFieldOf("projectile_speed_hu", 1100.0f)
            .forGetter(Ballistics::projectileSpeedHu),
        Codec.FLOAT.optionalFieldOf("blast_radius_hu", 146.0f)
            .forGetter(Ballistics::blastRadiusHu),
        Codec.FLOAT.optionalFieldOf("self_blast_radius_hu", 121.0f)
            .forGetter(Ballistics::selfBlastRadiusHu),
        Codec.FLOAT.optionalFieldOf("damage_ramp_optimal_hu", 512.0f)
            .forGetter(Ballistics::damageRampOptimalHu),
        Codec.FLOAT.optionalFieldOf("damage_range_spread", 0.5f)
            .forGetter(Ballistics::damageRangeSpread),
        Codec.FLOAT.optionalFieldOf("close_range_ramp_scale", 0.5f)
            .forGetter(Ballistics::closeRangeRampScale),
        Codec.FLOAT.optionalFieldOf("blast_push_hu", 400.0f).forGetter(Ballistics::blastPushHu),
        Codec.FLOAT.optionalFieldOf("self_damage_scale", 1.0f)
            .forGetter(Ballistics::selfDamageScale)
    ).apply(i, Ballistics::new));

    /**
     * Two map codecs joined, because {@code group()} is only overloaded to 16 arguments and this
     * record has 22 fields. {@code mapPair} merges both key sets into one object, so the JSON stays
     * flat and keeps matching the extractor's output shape.
     */
    public static final Codec<WeaponStats> CODEC = Codec.mapPair(SCRIPT, BALLISTICS).codec()
        .xmap(WeaponStats::join, WeaponStats::split);

    private static WeaponStats join(Pair<Script, Ballistics> pair) {
        final Script s = pair.getFirst();
        final Ballistics b = pair.getSecond();
        return new WeaponStats(
            s.clipSize(), s.reserveAmmo(), s.ammoPerShot(), s.damage(), s.bulletsPerShot(),
            s.spread(), s.rangeHu(), s.attackIntervalSeconds(), s.reloadStartSeconds(),
            s.reloadRepeatSeconds(), s.reloadsSingly(), s.reloadAnimStartSeconds(),
            s.reloadAnimRepeatSeconds(), s.smackDelaySeconds(),
            b.projectileSpeedHu(), b.blastRadiusHu(), b.selfBlastRadiusHu(),
            b.damageRampOptimalHu(), b.damageRangeSpread(), b.closeRangeRampScale(),
            b.blastPushHu(), b.selfDamageScale());
    }

    private static Pair<Script, Ballistics> split(WeaponStats w) {
        return Pair.of(
            new Script(w.clipSize, w.reserveAmmo, w.ammoPerShot, w.damage, w.bulletsPerShot,
                w.spread, w.rangeHu, w.attackIntervalSeconds, w.reloadStartSeconds,
                w.reloadRepeatSeconds, w.reloadsSingly, w.reloadAnimStartSeconds,
                w.reloadAnimRepeatSeconds, w.smackDelaySeconds),
            new Ballistics(w.projectileSpeedHu, w.blastRadiusHu, w.selfBlastRadiusHu,
                w.damageRampOptimalHu, w.damageRangeSpread, w.closeRangeRampScale,
                w.blastPushHu, w.selfDamageScale));
    }

    /**
     * The reload cadence the engine uses. m_bReloadsSingly is true for the Rocket Launcher and
     * Shotgun, and CTFWeaponBase::ReloadSingly calls SetReloadTimer(SequenceDuration()) - the
     * viewmodel animation length; TimeReloadStart/TimeReload are the fallback if SendWeaponAnim
     * fails.
     */
    public float effectiveReloadStart() {
        return reloadsSingly ? reloadAnimStartSeconds : reloadStartSeconds;
    }

    public float effectiveReloadRepeat() {
        return reloadsSingly ? reloadAnimRepeatSeconds : reloadRepeatSeconds;
    }
}
