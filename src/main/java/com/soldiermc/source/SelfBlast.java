package com.soldiermc.source;

/** Self-blast: CTFGameRules::RadiusDamage through CTFPlayer::ApplyPushFromDamage. */
public final class SelfBlast {

    /** A velocity change in hu/s, despite the name. */
    public float forceX, forceY, forceZ;
    public float damage;
    public int healthLost;
    public float falloffDistance;

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

        // A player's falloff distance is the nearer of its centre and its origin.
        float dCentre = length(blastX - originX, blastY - originY, blastZ - wscZ);
        float dOrigin = length(blastX - originX, blastY - originY, blastZ - originZ);
        falloffDistance = directHit ? 0f : Math.min(dCentre, dOrigin);

        if (falloffDistance > SourceFeel.SELF_BLAST_RADIUS) return false;

        float t = clamp01(falloffDistance / SourceFeel.SELF_BLAST_RADIUS);
        float adjusted = baseDamage + (baseDamage * SourceFeel.FALLOFF_HALF - baseDamage) * t;
        if (adjusted <= 0f) return false;

        boolean rocketJumping = !onGround && !inWater;
        damage = rocketJumping ? adjusted * SourceFeel.SELF_DAMAGE_SCALE : adjusted;

        healthLost = (int) (damage + 0.5f);

        float sizeZ = ducked ? SourceFeel.DUCK_FORCE_HULL_Z : SourceFeel.HULL_Z_STANDING;
        float hullRatio = SourceFeel.DAMAGE_FORCE_REFERENCE_VOLUME / (48f * 48f * sizeZ);
        float scale = onGround ? SourceFeel.FORCE_SCALE_SELF_BADRJ : SourceFeel.FORCE_SCALE_SELF_RJ;

        // damage already holds the 0.60 self scale, so the push is scaled too.
        float force = damage * hullRatio * scale;
        if (force > SourceFeel.DAMAGE_FORCE_CLAMP) force = SourceFeel.DAMAGE_FORCE_CLAMP;

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
            dirZ = -1f;      // negated below, so this pushes straight up
        }

        forceX = -dirX * force;
        forceY = -dirY * force;
        forceZ = -dirZ * force;
        return true;
    }

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
