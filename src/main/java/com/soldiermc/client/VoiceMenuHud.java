package com.soldiermc.client;

import com.soldiermc.SoldierMC;
import com.soldiermc.SoldierVoiceLines;
import com.soldiermc.SoldierVoiceLines.Concept;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;

/**
 * The voice command menu, lower left. Silent entries are dimmed rather than hidden, as TF2 draws
 * them: "Pass to me!" has an empty response block and "ÜberCharge Ready" is Medic-only.
 */
public final class VoiceMenuHud {

    private static final int TITLE = 0xFFD8B48C;
    private static final int NUMBER = 0xFF8FB8D8;
    private static final int LABEL = 0xFFE8E2D4;
    private static final int SILENT = 0xFF6F6A60;
    private static final int SILENT_NUM = 0xFF4F5F6F;
    private static final int BACKDROP = 0xCC1A1A1A;

    private static final int LINE_HEIGHT = 11;
    private static final int PAD = 5;

    private VoiceMenuHud() {
    }

    public static void init() {
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, "voice_menu"),
                VoiceMenuHud::render);
    }

    private static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
        int menu = SoldierVoice.openMenu();
        if (menu < 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        Concept[] entries = SoldierVoiceLines.MENUS[menu];
        String title = "VOICE MENU " + (menu + 1);

        int width = mc.font.width(title);
        for (int i = 0; i < entries.length; i++) {
            width = Math.max(width, mc.font.width((i + 1) + ". " + entries[i].label));
        }
        width += PAD * 2;
        int height = PAD * 2 + LINE_HEIGHT * (entries.length + 1);

        // Lower left, clear of the hotbar.
        int x = 6;
        int y = g.guiHeight() - height - 46;

        g.fill(x, y, x + width, y + height, BACKDROP);
        g.text(mc.font, title, x + PAD, y + PAD, TITLE);

        for (int i = 0; i < entries.length; i++) {
            Concept c = entries[i];
            int ly = y + PAD + LINE_HEIGHT * (i + 1);
            boolean silent = c.silent();
            g.text(mc.font, (i + 1) + ".", x + PAD, ly, silent ? SILENT_NUM : NUMBER);
            g.text(mc.font, c.label, x + PAD + 12, ly, silent ? SILENT : LABEL);
        }
    }
}
