package com.soldiermc.client;

import com.soldiermc.SoldierVoiceLines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;

/** The client sees only health changes, so damage types are coarser than TF2's. */
public final class SoldierReactions {

    private static float lastHealth = -1f;
    private static boolean wasOnFire;
    private static boolean wasAlive = true;
    private static boolean wasAirborneFromBlast;
    private static BlastJumpWhistle whistle;

    private SoldierReactions() {
    }

    public static void tick(Minecraft mc, long now) {
        LocalPlayer p = mc.player;
        if (p == null) return;

        float health = p.getHealth();

        if (lastHealth < 0f) {
            lastHealth = health;
            wasAlive = p.isAlive();
            SoldierVoice.queueRoundStart(now);
            return;
        }

        boolean alive = p.isAlive() && health > 0f;
        if (wasAlive && !alive) {
            // The client cannot tell a crit from a normal hit.
            boolean blast = SoldierMovement.lastHealthLost() > 0;
            SoldierVoice.onDeath(now, blast, false);
            stopWhistle(mc);
        } else if (!wasAlive && alive) {
            SoldierVoice.reset();
            SoldierVoice.queueRoundStart(now);
        }
        wasAlive = alive;

        if (alive && health < lastHealth - 0.01f) {
            boolean fall = !p.onGround() ? false : p.fallDistance > 3.0f;
            SoldierVoice.onHurt(now, fall);
        }
        lastHealth = health;

        boolean onFire = p.isOnFire() && alive;
        if (onFire) SoldierVoice.onFire(now);
        wasOnFire = onFire;

        tickWhistle(mc, p, now);
    }

    private static void tickWhistle(Minecraft mc, LocalPlayer p, long now) {
        boolean blastAirborne = !SoldierMovement.onGround() && SoldierMovement.lastBlastForce() > 0f;

        if (blastAirborne && !wasAirborneFromBlast) {
            whistle = new BlastJumpWhistle();
            mc.getSoundManager().play(whistle);
        } else if (!blastAirborne && wasAirborneFromBlast) {
            stopWhistle(mc);
        }
        wasAirborneFromBlast = blastAirborne;
    }

    private static void stopWhistle(Minecraft mc) {
        if (whistle != null) {
            whistle.finish();
            whistle = null;
        }
    }

    private static final class BlastJumpWhistle extends AbstractTickableSoundInstance {

        private int aloft;

        BlastJumpWhistle() {
            super(SoldierVoiceLines.BLAST_JUMP_WHISTLE, SoundSource.PLAYERS,
                    net.minecraft.util.RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.0f;
            this.relative = true;
        }

        void finish() {
            stop();
        }

        @Override
        public void tick() {
            if (SoldierMovement.onGround()) {
                stop();
                return;
            }
            aloft++;
            // 0.8 is the sound script's volume.
            float t = Math.min(aloft / 30.0f, 1.0f);
            this.volume = 0.8f * t;
            this.pitch = 0.9f + 0.3f * t;
        }
    }
}
