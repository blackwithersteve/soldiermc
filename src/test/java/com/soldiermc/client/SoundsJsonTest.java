package com.soldiermc.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.soldiermc.SoldierVoiceLines;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The generated {@code sounds.json} must parse. Minecraft reads the file as a whole and drops the
 * entire definition set on one syntax error, taking every sound in the pack with it.
 */
class SoundsJsonTest {

    /**
     * {@code SharedConstants} and {@code Component}, which the manifest test touches, need the
     * game's static bootstrap or the codec path dies in a static initialiser.
     */
    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    @DisplayName("the spliced sounds.json is valid JSON")
    void parses() {
        String json = SoldierResourcePack.soundsJson();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(root.size() > 0, "empty definitions");
    }

    @Test
    @DisplayName("every registered voice line has a sound definition, and vice versa")
    void everyLineIsDefined() {
        JsonObject root = JsonParser.parseString(SoldierResourcePack.soundsJson()).getAsJsonObject();

        for (String vo : SoldierVoiceLines.allFiles()) {
            assertTrue(root.has("vo." + vo),
                    "no sound definition for registered event vo." + vo);
        }
        assertTrue(root.has("blastjump.whistle"));
        assertTrue(root.has("weapons.rocket_shoot"), "the weapon sounds must survive the splice");
        assertTrue(root.has("weapons.explode"));

        // The event registry and the pack are driven off the same list.
        int expected = SoldierVoiceLines.allFiles().size() + 5 + 1;   // + weapons + whistle
        assertEquals(expected, root.size(), "definition count drifted from the registered events");
    }

    @Test
    @DisplayName("pack.mcmeta round-trips through Minecraft's own codec")
    void manifestIsCompatible() {
        // 26.2 replaced the flat "pack_format": N with a major.minor pair. A manifest carrying the
        // old shape still parses; it is only marked incompatible, and no asset loads.
        String meta = SoldierResourcePack.packMeta();
        JsonObject root = JsonParser.parseString(meta).getAsJsonObject();

        var type = net.minecraft.server.packs.metadata.pack.PackMetadataSection.forPackType(
                net.minecraft.server.packs.PackType.CLIENT_RESOURCES);
        assertTrue(root.has(type.name()), "manifest is missing the '" + type.name() + "' section");

        var decoded = type.codec().parse(
                com.mojang.serialization.JsonOps.INSTANCE, root.get(type.name()));
        assertTrue(decoded.result().isPresent(),
                "the running game cannot read our own manifest: " + decoded.error()
                        .map(Object::toString).orElse("?"));

        var section = decoded.result().get();
        var running = net.minecraft.server.packs.metadata.pack.PackFormat.of(
                net.minecraft.SharedConstants.RESOURCE_PACK_FORMAT_MAJOR,
                net.minecraft.SharedConstants.RESOURCE_PACK_FORMAT_MINOR);
        assertTrue(section.supportedFormats().isValueInRange(running),
                "the pack does not declare support for the running version " + running);
    }

    @Test
    @DisplayName("each definition names a real sound path")
    void pathsAreWellFormed() {
        JsonObject root = JsonParser.parseString(SoldierResourcePack.soundsJson()).getAsJsonObject();
        for (String key : root.keySet()) {
            var sounds = root.getAsJsonObject(key).getAsJsonArray("sounds");
            assertTrue(sounds.size() > 0, key + " has no sounds");
            for (var el : sounds) {
                String name = el.getAsJsonObject().get("name").getAsString();
                assertTrue(name.startsWith("soldiermc:"), key + " -> " + name);
                assertTrue(name.equals(name.toLowerCase()),
                        "resource paths must be lowercase: " + name);
            }
        }
    }
}
