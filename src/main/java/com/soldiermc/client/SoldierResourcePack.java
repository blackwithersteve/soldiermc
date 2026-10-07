package com.soldiermc.client;

import com.soldiermc.SoldierMC;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/** Minecraft plays audio only from resource packs, so the TF2 audio becomes one. */
public final class SoldierResourcePack {

    private static final String PACK_ID = "soldiermc-tf2";
    /** Folder packs are addressed as file/<name> in the pack repository. */
    private static final String PACK_HANDLE = "file/" + PACK_ID;

    private static final String[][] SOUNDS = {
        {"rocket_shoot.ogg", "weapons/rocket_shoot.ogg"},
        {"rocket_shoot_crit.ogg", "weapons/rocket_shoot_crit.ogg"},
        {"rocket_reload.ogg", "weapons/rocket_reload.ogg"},
        {"explode1.ogg", "weapons/explode1.ogg"},
        {"explode2.ogg", "weapons/explode2.ogg"},
        {"explode3.ogg", "weapons/explode3.ogg"},
    };

    private static boolean built;
    private static boolean installed;

    private SoldierResourcePack() {
    }

    /** Returns true when the caller must reload resources. */
    public static boolean ensureInstalled() {
        if (built) return false;
        built = true;

        Path staging = SoldierAssets.stagingDir();
        if (staging == null) return false;
        Path ogg = staging.resolve("ogg");
        if (!Files.isDirectory(ogg)) {
            SoldierMC.LOGGER.info("[soldiermc] no staged audio at {}, keeping placeholder sounds", ogg);
            return false;
        }

        Path packs = FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(PACK_ID);
        try {
            Path sounds = packs.resolve("assets/soldiermc/sounds");
            Files.createDirectories(sounds);

            boolean wroteAnything = false;

            for (String vo : com.soldiermc.SoldierVoiceLines.allFiles()) {
                Path src = ogg.resolve("vo/" + vo + ".ogg");
                if (!Files.exists(src)) continue;
                Path dst = sounds.resolve("vo/" + vo + ".ogg");
                Files.createDirectories(dst.getParent());
                if (!Files.exists(dst) || Files.size(dst) != Files.size(src)) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    wroteAnything = true;
                }
            }
            Path whistleSrc = ogg.resolve("blastjump_whistle.ogg");
            if (Files.exists(whistleSrc)) {
                Path dst = sounds.resolve("blastjump_whistle.ogg");
                if (!Files.exists(dst) || Files.size(dst) != Files.size(whistleSrc)) {
                    Files.copy(whistleSrc, dst, StandardCopyOption.REPLACE_EXISTING);
                    wroteAnything = true;
                }
            }

            for (String[] pair : SOUNDS) {
                Path src = ogg.resolve(pair[0]);
                if (!Files.exists(src)) continue;
                Path dst = sounds.resolve(pair[1]);
                Files.createDirectories(dst.getParent());
                if (!Files.exists(dst) || Files.size(dst) != Files.size(src)) {
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    wroteAnything = true;
                }
            }
            if (!wroteAnything && Files.exists(packs.resolve("pack.mcmeta"))) {
                return enableIfNeeded();
            }

            String meta = packMeta();
            Path metaPath = packs.resolve("pack.mcmeta");
            if (!Files.exists(metaPath) || !Files.readString(metaPath, StandardCharsets.UTF_8).equals(meta)) {
                Files.writeString(metaPath, meta, StandardCharsets.UTF_8);
                wroteAnything = true;
            }
            Files.writeString(packs.resolve("assets/soldiermc/sounds.json"), soundsJson(),
                    StandardCharsets.UTF_8);

            SoldierMC.LOGGER.info("[soldiermc] built the TF2 audio pack at {}", packs);
            // Reload even if selected, or options.txt keeps the pack marked incompatible.
            boolean newlyEnabled = enableIfNeeded();
            return newlyEnabled || wroteAnything;
        } catch (IOException e) {
            SoldierMC.LOGGER.warn("[soldiermc] could not build the audio pack: {}", e.toString());
            return false;
        }
    }

    /** 26.2 wants a major.minor format range; a flat pack_format loads as incompatible. */
    static String packMeta() {
        var format = net.minecraft.server.packs.metadata.pack.PackFormat.of(
                SharedConstants.RESOURCE_PACK_FORMAT_MAJOR,
                SharedConstants.RESOURCE_PACK_FORMAT_MINOR);
        var section = new net.minecraft.server.packs.metadata.pack.PackMetadataSection(
                net.minecraft.network.chat.Component.literal(
                        "TF2 Soldier audio, built from your own TF2 install"),
                new net.minecraft.util.InclusiveRange<>(format, format));
        var type = net.minecraft.server.packs.metadata.pack.PackMetadataSection.forPackType(
                net.minecraft.server.packs.PackType.CLIENT_RESOURCES);

        var encoded = type.codec()
                .encodeStart(com.mojang.serialization.JsonOps.INSTANCE, section)
                .getOrThrow(e -> new IllegalStateException("pack.mcmeta encode failed: " + e));

        var root = new com.google.gson.JsonObject();
        root.add(type.name(), encoded);
        return root + "\n";
    }

    public static boolean installed() {
        return installed;
    }

    private static boolean enableIfNeeded() {
        Minecraft mc = Minecraft.getInstance();
        var repo = mc.getResourcePackRepository();
        repo.reload();
        List<String> selected = repo.getSelectedIds().stream().toList();
        if (selected.contains(PACK_HANDLE)) {
            installed = true;
            return false;
        }
        if (!repo.addPack(PACK_HANDLE)) {
            SoldierMC.LOGGER.warn("[soldiermc] audio pack present but could not be selected");
            return false;
        }
        mc.options.updateResourcePacks(repo);
        installed = true;
        return true;
    }

    static String soundsJson() {
        StringBuilder sb = new StringBuilder(WEAPON_SOUNDS_JSON.stripTrailing());
        int close = sb.lastIndexOf("}");
        sb.setLength(close);
        for (String vo : com.soldiermc.SoldierVoiceLines.allFiles()) {
            entry(sb, "vo." + vo, "soldiermc:vo/" + vo);
        }
        entry(sb, "blastjump.whistle", "soldiermc:blastjump_whistle");
        sb.append("}\n");
        return sb.toString();
    }

    private static void entry(StringBuilder sb, String key, String path) {
        sb.append("  ,\"").append(key).append("\": {\n")
          .append("    \"category\": \"player\",\n")
          .append("    \"sounds\": [{\"name\": \"").append(path).append("\"}]\n")
          .append("  }\n");
    }

    private static final String WEAPON_SOUNDS_JSON = """
            {
              "weapons.rocket_shoot": {
                "category": "player",
                "sounds": [{"name": "soldiermc:weapons/rocket_shoot", "attenuation_distance": 32}]
              },
              "weapons.rocket_shoot_crit": {
                "category": "player",
                "sounds": [{"name": "soldiermc:weapons/rocket_shoot_crit", "attenuation_distance": 32}]
              },
              "weapons.rocket_reload": {
                "category": "player",
                "sounds": [{"name": "soldiermc:weapons/rocket_reload"}]
              },
              "weapons.explode": {
                "category": "player",
                "sounds": [
                  {"name": "soldiermc:weapons/explode1", "attenuation_distance": 48},
                  {"name": "soldiermc:weapons/explode2", "attenuation_distance": 48},
                  {"name": "soldiermc:weapons/explode3", "attenuation_distance": 48}
                ]
              },
              "weapons.empty": {
                "category": "player",
                "sounds": [{"name": "soldiermc:weapons/rocket_reload"}]
              }
            }
            """;
}
