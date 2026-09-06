package com.soldiermc.source;

/**
 * The self-blast: what a Soldier's own rocket does to him. A pure function, no world access.
 * {@code CTFGameRules::RadiusDamage}'s attacker pass, {@code ApplyToEntity},
 * {@code CTFPlayer::OnTakeDamage} and {@code ApplyPushFromDamage} in one call, since for the
 * attacker every branch between them resolves the same way every time. Order-sensitive: the radius
 * swaps from 146 to 121 for the attacker after the first pass while the falloff fraction stays 0.5;
 * the 0.60 self-damage scale lands before {@code SetDamageForForceCalc} latches the value the push
 * is computed from, so airborne is {@code 0.60 x d x 10.0} against grounded {@code 1.00 x d x 5.0},
 * a 6:5 ratio; and the 1000 clamp sits inside {@code DamageForce}, before the self-push multiplier.
 * {@code CalculateExplosiveDamageForce} and {@code ScaleDamageForce} (tf_gamerules.cpp:5894, :5904)
 * are not ported: {@code ApplyPushFromDamage} recomputes the force from scratch and never reads
 * {@code GetDamageForce()}, so porting them would add a random 0.85-1.15 fudge to every rocket jump.
 */
public final class SelfBlast {

    /** Force vector, hu/s. */
    public float forceX, forceY, forceZ;
    /** Damage after every scale, as a float. */
    public float damage;
    /** Health actually lost: {@code trunc(damage + 0.5)}, subtracted as an int. */
    public int healthLost;
    /** Distance used for the falloff, hu. */
    public float falloffDistance;

    /**
     * @param blastX,blastY,blastZ    detonation origin, hu (already pulled out along the normal)
     * @param originX,originY,originZ the player's feet, hu
     * @param baseDamage              the weapon script's {@code Damage}, 90 for the stock launcher
     * @param directHit               the blast's {@code m_hEnemy} is this player, so distance is 0
     * @return false if the player is outside the self-blast radius and nothing happens
     */
    public boolean compute(float blastX, float blastY, float blastZ,
                           float originX, float originY, float originZ,
                           boolean ducked, boolean onGround, boolean inWater,
                           boolean directHit, float baseDamage) {

        forceX = 0f;
        forceY = 0f;
        forceZ = 0f;
        damage = 0f;
        healthLost = 0;

        float hullZ = ducked ? SourceFeel.HULL_Z_DUCKED : SourceFeel.HULL_Z_STANDING;
        float wscZ = originZ + hullZ * 0.5f;

        // tf_gamerules.cpp:5845-5848 — for a PLAYER the falloff distance is the nearer of the world
        // space centre and the origin, not one or the other.
        float dCentre = length(blastX - originX, blastY - originY, blastZ - wscZ);
        float dOrigin = length(blastX - originX, blastY - originY, blastZ - originZ);
        falloffDistance = directHit ? 0f : Math.min(dCentre, dOrigin);

        // The sphere test that gates the whole thing, tf_gamerules.cpp:5751.
        if (falloffDistance > SourceFeel.SELF_BLAST_RADIUS) return false;

        // RemapValClamped(d, 0, radius, dmg, dmg*0.5), tf_gamerules.cpp:5855.
        float t = clamp01(falloffDistance / SourceFeel.SELF_BLAST_RADIUS);
        float adjusted = baseDamage + (baseDamage * SourceFeel.FALLOFF_HALF - baseDamage) * t;
        if (adjusted <= 0f) return false;

        // tf_player.cpp:8923 — the five-term gate needs both !onGround and !inWater, so a Soldier
        // airborne in water takes full damage and the airborne force scale, the only stock path
        // that reaches the 1000 clamp.
        boolean rocketJumping = !onGround && !inWater;
        damage = rocketJumping ? adjusted * SourceFeel.SELF_DAMAGE_SCALE : adjusted;

        // tf_player.cpp:10599 — m_iHealth is a CNetworkVar<int>, so the float is truncated first and
        // then subtracted: health -= (int)(damage + 0.5).
        healthLost = (int) (damage + 0.5f);

        // tf_player.cpp:10254-10262 — the ducked hull's z is overridden with 55, not its real 62.
        float sizeZ = ducked ? SourceFeel.DUCK_FORCE_HULL_Z : SourceFeel.HULL_Z_STANDING;
        float hullRatio = SourceFeel.DAMAGE_FORCE_REFERENCE_VOLUME / (48f * 48f * sizeZ);
        float scale = onGround ? SourceFeel.FORCE_SCALE_SELF_BADRJ : SourceFeel.FORCE_SCALE_SELF_RJ;

        float force = damage * hullRatio * scale;
        if (force > SourceFeel.DAMAGE_FORCE_CLAMP) force = SourceFeel.DAMAGE_FORCE_CLAMP;

        // tf_player.cpp:10564-10566. The bias is subtracted from the BLAST and the direction is
        // normalised afterwards; the force then pushes along the negation, i.e. away from the blast.
        float dirX = blastX - originX;
        float dirY = blastY - originY;
        float dirZ = (blastZ - SourceFeel.DAMAGE_DIR_Z_BIAS) - wscZ;
        float len = (float) Math.sqrt((double) dirX * dirX + (double) dirY * dirY + (double) dirZ * dirZ);
        if (len > 1e-9f) {
            float inv = 1f / len;
            dirX *= inv;
            dirY *= inv;
            dirZ *= inv;
        } else {
            dirX = 0f;
            dirY = 0f;
            dirZ = -1f;      // degenerate: push straight up, matching the bias-only direction
        }

        forceX = -dirX * force;
        forceY = -dirY * force;
        forceZ = -dirZ * force;
        return true;
    }

    /** The magnitude of the applied force, hu/s. */
    public float forceMagnitude() {
        return length(forceX, forceY, forceZ);
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt((double) x * x + (double) y * y + (double) z * z);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : v > 1f ? 1f : v;
    }
}
