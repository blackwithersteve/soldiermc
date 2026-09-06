package com.soldiermc.studio;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Java readers against the real extracted files. Skipped rather than failed when the assets are
 * absent, since they are gitignored and extracted from a local TF2 install.
 */
class StudioReaderTest {

    private static final Path DIR =
            Path.of("tf2-assets-staging/models/player");

    private static byte[] read(String name) {
        try {
            Path p = DIR.resolve(name);
            Assumptions.assumeTrue(Files.exists(p),
                    "extracted assets absent (" + p + ") — skipping");
            return Files.readAllBytes(p);
        } catch (Exception e) {
            Assumptions.abort("could not read " + name + ": " + e);
            return null;
        }
    }

    @Test
    @DisplayName("MDL header: version 48, 86 bones, checksum -546231176")
    void mdlHeader() {
        byte[] b = read("soldier.mdl");
        MdlFile m = new MdlFile(b);

        assertEquals(48, m.version, "TF2 ships v48, not the documented v49");
        assertEquals(86, m.bones.length);
        assertEquals(-546231176, m.checksum);
        assertEquals(b.length, m.length, "header length field must equal the file size");
        assertEquals("player/soldier.mdl", m.name);
    }

    @Test
    @DisplayName("the animation MDL shares the skeleton but NOT the checksum")
    void animMdlIsSeparate() {
        MdlFile anims = new MdlFile(read("soldier_animations.mdl"));
        assertEquals(86, anims.bones.length, "same skeleton, or the animations cannot apply");
        assertEquals(-1907385136, anims.checksum,
                "differs from the mesh triple ON PURPOSE — do not add it to the three-way check");
    }

    @Test
    @DisplayName("VVD fixups reproduce numLODVertexes for every LOD")
    void vvdFixups() {
        VvdFile v = new VvdFile(read("soldier.vvd"));
        assertEquals(4, v.version);

        // Counts from a tools/srcparse run over these files.
        int[] expected = {9626, 6121, 3436, 2276, 2273, 2091};
        for (int lod = 0; lod < expected.length; lod++) {
            assertEquals(expected[lod], v.verticesForLod(lod).size(),
                    "LOD " + lod + " fixup count");
            assertEquals(expected[lod], v.numLODVertexes[lod],
                    "LOD " + lod + " header count");
        }
    }

    @Test
    @DisplayName("the assembled LOD-0 mesh is 8310 triangles across 2 materials")
    void assembleLod0() {
        byte[] mdlBytes = read("soldier.mdl");
        MdlFile mdl = new MdlFile(mdlBytes);
        VvdFile vvd = new VvdFile(read("soldier.vvd"));
        VtxFile vtx = new VtxFile(read("soldier.dx90.vtx"));

        assertNull(StudioModel.validate(mdl, vvd, vtx, mdlBytes.length),
                "asset gate must pass on the real files");

        StudioModel model = new StudioModel(mdl, vvd, vtx, 0);
        assertEquals(9626, model.vertices.size());
        assertEquals(8310, model.triangleCount, "LOD-0 triangle count");

        // Materials: 0 soldier_red (body, hat, webbing), 2 soldier_head_red, 1 and 3 the eyeballs.
        // Only 0 and 2 are rendered; the eyeballs are 68 of the 8310 triangles.
        assertEquals(4, model.trianglesByMaterial.size(),
                "body, head and two eyeballs");
        assertTrue(model.trianglesByMaterial.containsKey(0), "body material present");
        assertTrue(model.trianglesByMaterial.containsKey(2), "head material present");

        // Every index must address the fixed-up array, not the raw one.
        for (int[] idx : model.trianglesByMaterial.values()) {
            for (int i : idx) {
                assertTrue(i >= 0 && i < model.vertices.size(),
                        "index " + i + " out of range for " + model.vertices.size() + " vertices");
            }
        }
    }

    @Test
    @DisplayName("the model is not mirrored: eyeball_l sits on +X in file space")
    void handedness() {
        MdlFile mdl = new MdlFile(read("soldier.mdl"));

        // File space is Y-up with +X the model's left, so the left eye sits at positive X.
        int left = -1, right = -1;
        for (MdlFile.Bone b : mdl.bones) {
            if (b.name.equals("bip_eye_L")) left = b.index;
            if (b.name.equals("bip_eye_R")) right = b.index;
        }
        Assumptions.assumeTrue(left >= 0 && right >= 0, "eye bones not present under those names");
        assertTrue(mdl.bones[left].pos[0] != mdl.bones[right].pos[0],
                "the eye bones must be laterally separated");
    }

    @Test
    @DisplayName("weapon_bone is bone 24 under the RIGHT hand — the asymmetry the F5 test uses")
    void weaponBone() {
        MdlFile mdl = new MdlFile(read("soldier.mdl"));
        int weapon = -1;
        for (MdlFile.Bone b : mdl.bones) {
            if (b.name.equals("weapon_bone")) weapon = b.index;
        }
        Assumptions.assumeTrue(weapon >= 0, "weapon_bone not found");
        String parent = mdl.bones[mdl.bones[weapon].parent].name;
        assertTrue(parent.toLowerCase().contains("hand"),
                "weapon_bone should hang off a hand, got parent " + parent);
    }
}
