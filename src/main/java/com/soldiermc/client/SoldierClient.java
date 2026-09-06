package com.soldiermc.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.soldiermc.SoldierMC;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

/** Client-side switches: Source physics on/off, debug overlay on/off. */
public final class SoldierClient {

    public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(
        Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, "main"));

    private static KeyMapping togglePhysics;
    private static KeyMapping toggleDiagnostics;
    private static KeyMapping reload;
    private static KeyMapping voice1;
    private static KeyMapping voice2;
    private static KeyMapping voice3;

    private static boolean physics = true;
    private static boolean diagnostics = true;

    private SoldierClient() {
    }

    /** False falls back to vanilla movement. */
    public static boolean enabled() {
        return physics;
    }

    public static boolean diagnostics() {
        return diagnostics;
    }

    /**
     * {@code +reload}, held state rather than an edge: TF2's reload machine restarts from the
     * button being down on the substep the previous rocket finished.
     */
    public static boolean reloadDown() {
        return reload != null && reload.isDown();
    }

    public static void init() {
        togglePhysics = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.soldiermc.toggle_physics", InputConstants.Type.KEYSYM,
            InputConstants.KEY_P, CATEGORY));
        toggleDiagnostics = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.soldiermc.toggle_diagnostics", InputConstants.Type.KEYSYM,
            InputConstants.KEY_O, CATEGORY));
        reload = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.soldiermc.reload", InputConstants.Type.KEYSYM,
            InputConstants.KEY_R, CATEGORY));
        // TF2's own defaults: tf/cfg/config_default.cfg binds z/x/c to voice_menu_1/2/3.
        voice1 = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.soldiermc.voice_menu_1", InputConstants.Type.KEYSYM,
            InputConstants.KEY_Z, CATEGORY));
        voice2 = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.soldiermc.voice_menu_2", InputConstants.Type.KEYSYM,
            InputConstants.KEY_X, CATEGORY));
        voice3 = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.soldiermc.voice_menu_3", InputConstants.Type.KEYSYM,
            InputConstants.KEY_C, CATEGORY));
        ClientTickEvents.END_CLIENT_TICK.register(SoldierClient::tick);
        // Voice input runs at the START of the tick: Minecraft.handleKeybinds() drains the hotbar
        // click queue partway through tick() (bytecode offset 181), so a handler at END sees an
        // empty queue. Draining the clicks here also stops the hotbar switching under the menu.
        ClientTickEvents.START_CLIENT_TICK.register(SoldierClient::voiceTick);
    }

    /**
     * A monotonic client tick counter, not {@code level.getGameTime()}: world time restarts on
     * joining another world and can be frozen by {@code /tick freeze}, leaving voice deadlines
     * stamped against it permanently in the future.
     */
    private static long clientTicks;

    private static void voiceTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
            // Left the world: drop the timers rather than carrying them into the next one.
            if (clientTicks != 0) {
                clientTicks = 0;
                SoldierVoice.reset();
            }
            return;
        }
        long now = ++clientTicks;

        while (voice1.consumeClick()) SoldierVoice.toggleMenu(0, now);
        while (voice2.consumeClick()) SoldierVoice.toggleMenu(1, now);
        while (voice3.consumeClick()) SoldierVoice.toggleMenu(2, now);

        if (SoldierVoice.openMenu() >= 0) {
            for (int i = 0; i < mc.options.keyHotbarSlots.length; i++) {
                boolean picked = false;
                while (mc.options.keyHotbarSlots[i].consumeClick()) picked = true;
                if (picked) {
                    SoldierVoice.select(i, now);
                    break;
                }
            }
        }

        SoldierVoice.tick(mc, now);
        SoldierVoice.tickRoundStart(now);
        SoldierReactions.tick(mc, now);
    }

    private static void tick(Minecraft mc) {
        // consumeClick, not isDown: one toggle per physical press, off our own queue.
        while (togglePhysics.consumeClick()) {
            physics = !physics;
        }
        while (toggleDiagnostics.consumeClick()) {
            diagnostics = !diagnostics;
        }

        // The simulation re-seeds from the player's Minecraft state whenever it is switched on, so
        // toggling mid-air picks up the live position and velocity.
        SoldierMovement.setActive(physics && mc.player != null);

        // First tick with a world, not init: the pack repository is not ready until loading ends.
        if (mc.player != null && SoldierResourcePack.ensureInstalled()) {
            mc.reloadResourcePacks();
        }
    }
}
