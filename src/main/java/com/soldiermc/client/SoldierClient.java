package com.soldiermc.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.soldiermc.SoldierMC;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

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

    public static boolean diagnostics() {
        return diagnostics;
    }

    /** Held state, not an edge: TF2 chains reloads while the button stays down. */
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
        // START, not END: handleKeybinds drains the hotbar click queue during tick().
        ClientTickEvents.START_CLIENT_TICK.register(SoldierClient::voiceTick);
    }

    /** Not level.getGameTime(), which restarts per world and stops under /tick freeze. */
    private static long clientTicks;

    private static void voiceTick(Minecraft mc) {
        if (mc.player == null || mc.level == null) {
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
        while (togglePhysics.consumeClick()) {
            physics = !physics;
        }
        while (toggleDiagnostics.consumeClick()) {
            diagnostics = !diagnostics;
        }

        SoldierMovement.setActive(physics && mc.player != null);

        // Not in init: the pack repository is not ready until loading ends.
        if (mc.player != null && SoldierResourcePack.ensureInstalled()) {
            mc.reloadResourcePacks();
        }
    }
}
