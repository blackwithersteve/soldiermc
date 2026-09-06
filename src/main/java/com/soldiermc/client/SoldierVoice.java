package com.soldiermc.client;

import com.soldiermc.SoldierVoiceLines;
import com.soldiermc.SoldierVoiceLines.Concept;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Line selection, the three-layer rate limit, and the menu state. Response groups draw without
 * replacement and reshuffle only once the group is exhausted.
 */
public final class SoldierVoice {

    /** {@code COMMAND_MAX_RATE}, keyed on the "voicemenu" command string. */
    private static final long COMMAND_RATE_TICKS = 6;      // 0.3 s
    /** The cap on the post-speech lockout: {@code min(sceneDuration, 1.5 s)}. */
    private static final long MAX_SPEECH_LOCK_TICKS = 30;  // 1.5 s
    /** {@code m_flSelectionTime} — the menu times out from OPEN, not from last input. */
    private static final long MENU_TIMEOUT_TICKS = 100;    // 5.0 s

    private static long nextCommandTick;
    private static long nextVoiceTick;
    private static long speakingUntilTick;

    /** -1 = closed, else 0/1/2 for menus 1/2/3. */
    private static int openMenu = -1;
    private static long menuOpenedTick;

    private static long nextPainTick;
    private static long nextBurnTick;

    private static final RandomGenerator RNG = RandomGenerator.getDefault();

    private SoldierVoice() {
    }

    // ---------------------------------------------------------------- menu state

    public static int openMenu() {
        return openMenu;
    }

    public static void toggleMenu(int index, long now) {
        // Pressing the same key again closes it; a different key switches.
        openMenu = (openMenu == index) ? -1 : index;
        menuOpenedTick = now;
    }

    public static void closeMenu() {
        openMenu = -1;
    }

    public static void tick(Minecraft mc, long now) {
        if (openMenu >= 0 && now - menuOpenedTick > MENU_TIMEOUT_TICKS) {
            // TF2 writes m_flSelectionTime only in ShowMenu, so hovering does not extend the 5 s.
            openMenu = -1;
        }
        if (mc.player != null && (mc.player.isDeadOrDying() || mc.player.isSpectator())) {
            openMenu = -1;
        }
    }

    /**
     * A number key while a menu is open.
     *
     * @return true if the key was consumed, which stops it also switching weapons
     */
    public static boolean select(int slot, long now) {
        if (openMenu < 0) return false;
        int menu = openMenu;
        openMenu = -1;

        // The command rate limit is checked before the selection is validated, and a rejected
        // command does not update the timestamp. Key 0's out-of-range index still burns a token.
        if (now < nextCommandTick) {
            lastReason = "command rate limit";
            return true;
        }
        nextCommandTick = now + COMMAND_RATE_TICKS;

        Concept[] entries = SoldierVoiceLines.MENUS[menu];
        if (slot < 0 || slot >= entries.length) return true;   // key 0, or menu 3 slot 9
        speak(entries[slot], now, true);
        return true;
    }

    // ---------------------------------------------------------------- speaking

    /**
     * @param isVoiceCommand voice commands bypass the general speech lock but not the "already
     *                       speaking" check, and they set the post-speech lockout
     */
    public static void speak(Concept c, long now, boolean isVoiceCommand) {
        if (c == null) return;
        if (c.silent()) {
            lastReason = "silent for Soldier";
            lastLine = c.label;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        if (isVoiceCommand) {
            if (now < nextVoiceTick) {
                lastReason = "rate limited";
                return;
            }
            if (now < speakingUntilTick) {
                lastReason = "still speaking";
                return;
            }
        }

        String file = draw(c);
        SoundEvent event = SoldierVoiceLines.event(file);
        if (event == null) {
            lastReason = "no sound event";
            return;
        }
        lastLine = file;
        lastReason = "";

        // GESTURE_SLOT_ATTACK_AND_RELOAD, the slot fire and reload use, so a voice command
        // interrupts a reload animation. "Yes", "No" and "Jeers" are audio only.
        if (c.gesture != null) {
            SoldierBody.animState().startGesture(c.gesture, c.gestureSeconds);
        }

        mc.level.playLocalSound(mc.player.getX(), mc.player.getY(), mc.player.getZ(),
                event, SoundSource.PLAYERS, 1.0f, 1.0f, false);

        long lock = Math.min(c.sceneMs / 50L, MAX_SPEECH_LOCK_TICKS);
        speakingUntilTick = now + lock;
        if (isVoiceCommand) nextVoiceTick = now + lock;
    }

    private static volatile String lastLine = "-";
    private static volatile String lastReason = "";

    public static String lastLine() {
        return lastLine;
    }

    /** Why the last selection produced nothing, or "" if it spoke. */
    public static String lastReason() {
        return lastReason;
    }

    private static final java.util.Random SHUFFLE = new java.util.Random();

    /** Draw without replacement, reshuffling when the group empties. */
    private static String draw(Concept c) {
        if (c.bag.isEmpty()) {
            c.bag.addAll(List.of(c.files));
            Collections.shuffle(c.bag, SHUFFLE);
        }
        return c.bag.remove(c.bag.size() - 1);
    }

    // ---------------------------------------------------------------- reactions

    /**
     * Took damage and lived. Fall damage short-circuits in {@code PainSound} to the generic death
     * bank without firing the pain concept or advancing the cooldown, but it still reads it.
     */
    public static void onHurt(long now, boolean fallDamage) {
        if (now < nextPainTick) return;
        Concept c = fallDamage ? SoldierVoiceLines.DEATH_GENERIC : SoldierVoiceLines.PAIN;
        speak(c, now, false);
        if (!fallDamage) nextPainTick = now + Math.max(speakingUntilTick - now, 20);
    }

    public static void onDeath(long now, boolean blast, boolean crit) {
        // CTFPlayer::DeathSound tests BLAST before CRITICAL, so a crit rocket kill plays the sharp
        // bank and never the critical one.
        Concept c = blast ? SoldierVoiceLines.DEATH_BLAST
                : crit ? SoldierVoiceLines.DEATH_CRIT
                : SoldierVoiceLines.DEATH_GENERIC;
        speak(c, now, false);
    }

    /** Burning. TF2's hard timer between burn barks is 2.5 s. */
    public static void onFire(long now) {
        if (now < nextBurnTick) return;
        nextBurnTick = now + 50;
        speak(SoldierVoiceLines.ON_FIRE, now, false);
    }

    private static long roundStartAt = -1;

    /** Queue the spawn battle cry with TF2's uniform 1-5 s predelay. */
    public static void queueRoundStart(long now) {
        roundStartAt = now + 20 + RNG.nextInt(81);   // 1.0 s .. 5.0 s
    }

    public static void tickRoundStart(long now) {
        if (roundStartAt >= 0 && now >= roundStartAt) {
            roundStartAt = -1;
            speak(SoldierVoiceLines.ROUND_START, now, false);
        }
    }

    public static void reset() {
        openMenu = -1;
        roundStartAt = -1;
        nextPainTick = 0;
        nextBurnTick = 0;
        nextVoiceTick = 0;
        speakingUntilTick = 0;
    }
}
