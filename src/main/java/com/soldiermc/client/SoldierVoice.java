package com.soldiermc.client;

import com.soldiermc.SoldierVoiceLines;
import com.soldiermc.SoldierVoiceLines.Concept;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

import java.util.Collections;
import java.util.List;
import java.util.random.RandomGenerator;

public final class SoldierVoice {

    private static final long COMMAND_RATE_TICKS = 6;
    private static final long MAX_SPEECH_LOCK_TICKS = 30;
    private static final long MENU_TIMEOUT_TICKS = 100;

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

    public static int openMenu() {
        return openMenu;
    }

    public static void toggleMenu(int index, long now) {
        openMenu = (openMenu == index) ? -1 : index;
        menuOpenedTick = now;
    }

    public static void tick(Minecraft mc, long now) {
        if (openMenu >= 0 && now - menuOpenedTick > MENU_TIMEOUT_TICKS) {
            // As in TF2, the timeout runs from opening; input does not extend it.
            openMenu = -1;
        }
        if (mc.player != null && (mc.player.isDeadOrDying() || mc.player.isSpectator())) {
            openMenu = -1;
        }
    }

    public static boolean select(int slot, long now) {
        if (openMenu < 0) return false;
        int menu = openMenu;
        openMenu = -1;

        // As in TF2, the rate limit is checked before the slot, so a bad key still counts.
        if (now < nextCommandTick) {
            lastReason = "command rate limit";
            return true;
        }
        nextCommandTick = now + COMMAND_RATE_TICKS;

        Concept[] entries = SoldierVoiceLines.MENUS[menu];
        if (slot < 0 || slot >= entries.length) return true;
        speak(entries[slot], now, true);
        return true;
    }

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

        // Gestures share TF2's attack and reload slot, so they interrupt a reload.
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

    public static String lastReason() {
        return lastReason;
    }

    private static final java.util.Random SHUFFLE = new java.util.Random();

    private static String draw(Concept c) {
        if (c.bag.isEmpty()) {
            c.bag.addAll(List.of(c.files));
            Collections.shuffle(c.bag, SHUFFLE);
        }
        return c.bag.remove(c.bag.size() - 1);
    }

    /** As in TF2, fall damage reads the pain cooldown but does not advance it. */
    public static void onHurt(long now, boolean fallDamage) {
        if (now < nextPainTick) return;
        Concept c = fallDamage ? SoldierVoiceLines.DEATH_GENERIC : SoldierVoiceLines.PAIN;
        speak(c, now, false);
        if (!fallDamage) nextPainTick = now + Math.max(speakingUntilTick - now, 20);
    }

    public static void onDeath(long now, boolean blast, boolean crit) {
        // TF2 tests blast before crit, so a crit rocket kill plays the blast bank.
        Concept c = blast ? SoldierVoiceLines.DEATH_BLAST
                : crit ? SoldierVoiceLines.DEATH_CRIT
                : SoldierVoiceLines.DEATH_GENERIC;
        speak(c, now, false);
    }

    /** TF2 allows one burn bark every 2.5 s. */
    public static void onFire(long now) {
        if (now < nextBurnTick) return;
        nextBurnTick = now + 50;
        speak(SoldierVoiceLines.ON_FIRE, now, false);
    }

    private static long roundStartAt = -1;

    /** The spawn battle cry waits TF2's uniform 1 to 5 s predelay. */
    public static void queueRoundStart(long now) {
        roundStartAt = now + 20 + RNG.nextInt(81);
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
