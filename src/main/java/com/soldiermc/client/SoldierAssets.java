package com.soldiermc.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.soldiermc.SoldierMC;
import com.soldiermc.studio.AnimSampler;
import com.soldiermc.studio.MdlFile;
import com.soldiermc.studio.Reader;
import com.soldiermc.studio.SequenceEvaluator;
import com.soldiermc.studio.Skeleton;
import com.soldiermc.studio.StudioModel;
import com.soldiermc.studio.VtxFile;
import com.soldiermc.studio.VvdFile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Loads the Soldier's mesh and textures once, at runtime, from the gitignored
 * {@code tf2-assets-staging/} — nothing extracted from TF2 ships in the jar. If an asset is missing
 * or a gate fails, {@link #ready()} stays false, the render mixin does not cancel, and vanilla Steve
 * appears with one warning in the log.
 */
public final class SoldierAssets {

    private static boolean attempted;
    private static boolean ready;
    private static String failure;

    private static StudioModel model;
    private static MdlFile animMdl;
    private static AnimSampler sampler;
    private static AnimSampler.AnimDesc idle;
    private static SequenceEvaluator evaluator;
    private static final java.util.EnumMap<com.soldiermc.animstate.TfAnimState.Activity, MdlFile.SeqDesc>
            SEQS = new java.util.EnumMap<>(com.soldiermc.animstate.TfAnimState.Activity.class);
    private static Identifier bodyTex;
    private static Identifier headTex;

    /** Diagnostics the overlay prints. */
    private static String summary = "not loaded";

    private SoldierAssets() {
    }

    public static boolean ready() {
        return ready;
    }

    public static StudioModel model() {
        return model;
    }

    public static MdlFile animModel() {
        return animMdl;
    }

    public static AnimSampler sampler() {
        return sampler;
    }

    public static AnimSampler.AnimDesc idle() {
        return idle;
    }

    public static SequenceEvaluator evaluator() {
        return evaluator;
    }

    private static MdlFile.SeqDesc jumpLand;

    /** Gesture sequences, resolved by name once at load. */
    private static final java.util.Map<String, MdlFile.SeqDesc> GESTURES = new java.util.HashMap<>();

    /** A gesture sequence by name, or null if absent or the name is null. */
    public static MdlFile.SeqDesc gesture(String name) {
        return name == null ? null : GESTURES.get(name);
    }

    /** {@code @jumpland_PRIMARY}, the landing gesture. Null if the model lacks it. */
    public static MdlFile.SeqDesc jumpLand() {
        return jumpLand;
    }

    public static MdlFile.SeqDesc sequenceFor(com.soldiermc.animstate.TfAnimState.Activity a) {
        MdlFile.SeqDesc sd = SEQS.get(a);
        return sd != null ? sd : SEQS.get(com.soldiermc.animstate.TfAnimState.Activity.STAND_IDLE);
    }

    public static Identifier bodyTexture() {
        return bodyTex;
    }

    public static Identifier headTexture() {
        return headTex;
    }

    public static String summary() {
        return summary;
    }

    public static String failure() {
        return failure;
    }

    /** Idempotent. Safe to call from any client tick. */
    public static synchronized void ensureLoaded() {
        if (attempted) return;
        attempted = true;
        try {
            load();
            ready = true;
        } catch (Throwable t) {
            failure = t.getMessage() == null ? t.toString() : t.getMessage();
            summary = "unavailable: " + failure;
            SoldierMC.LOGGER.warn(
                    "[soldiermc] Soldier model not loaded, rendering vanilla instead: {}", failure);
        }
    }

    private static void load() throws IOException {
        Path dir = locateStaging();
        Path models = dir.resolve("models/player");

        byte[] mdlBytes = Files.readAllBytes(models.resolve("soldier.mdl"));
        MdlFile mdl = new MdlFile(mdlBytes);
        VvdFile vvd = new VvdFile(Files.readAllBytes(models.resolve("soldier.vvd")));
        VtxFile vtx = new VtxFile(Files.readAllBytes(models.resolve("soldier.dx90.vtx")));

        String bad = StudioModel.validate(mdl, vvd, vtx, mdlBytes.length);
        if (bad != null) throw new IOException(bad);

        model = new StudioModel(mdl, vvd, vtx, 0);

        // The animation MDL carries the same 86-bone skeleton but a different checksum, so it is
        // excluded from the three-way mesh check above.
        byte[] animBytes = Files.readAllBytes(models.resolve("soldier_animations.mdl"));
        animMdl = new MdlFile(animBytes);
        if (animMdl.bones.length != mdl.bones.length) {
            throw new IOException("animation skeleton has " + animMdl.bones.length
                    + " bones, mesh has " + mdl.bones.length + " — they must match");
        }
        sampler = new AnimSampler(new Reader(animBytes), animMdl);
        idle = animMdl.findAnim("@stand_PRIMARY");
        if (idle == null) throw new IOException("@stand_PRIMARY not found in the animation MDL");
        evaluator = new SequenceEvaluator(animMdl, sampler);

        var A = com.soldiermc.animstate.TfAnimState.Activity.class;
        bindSeq(com.soldiermc.animstate.TfAnimState.Activity.STAND_IDLE, "stand_PRIMARY");
        bindSeq(com.soldiermc.animstate.TfAnimState.Activity.RUN, "run_PRIMARY");
        bindSeq(com.soldiermc.animstate.TfAnimState.Activity.CROUCH_IDLE, "crouch_PRIMARY");
        bindSeq(com.soldiermc.animstate.TfAnimState.Activity.CROUCH_WALK, "crouch_walk_PRIMARY");
        bindSeq(com.soldiermc.animstate.TfAnimState.Activity.JUMP_START, "jump_start_PRIMARY");
        bindSeq(com.soldiermc.animstate.TfAnimState.Activity.JUMP_FLOAT, "jump_float_PRIMARY");
        bindSeq(com.soldiermc.animstate.TfAnimState.Activity.AIRWALK, "airwalk_PRIMARY");

        for (String g : new String[]{"gesture_primary_go", "gesture_primary_help",
                "gesture_primary_cheer", "gesture_primary_positive"}) {
            MdlFile.SeqDesc sd = animMdl.findSequence(g);
            if (sd != null) {
                GESTURES.put(g, sd);
            } else {
                SoldierMC.LOGGER.warn("[soldiermc] gesture {} missing", g);
            }
        }

        jumpLand = animMdl.findSequence("jumpland_PRIMARY");
        if (jumpLand == null) {
            SoldierMC.LOGGER.warn("[soldiermc] jumpland_PRIMARY missing - landings will hard-cut");
        }
        if (SEQS.get(com.soldiermc.animstate.TfAnimState.Activity.STAND_IDLE) == null) {
            throw new IOException("stand_PRIMARY sequence missing");
        }

        bodyTex = registerTexture(dir.resolve("textures/soldier_red.png"), "soldier_red");
        headTex = registerTexture(dir.resolve("textures/soldier_head.png"), "soldier_head");

        summary = String.format(Locale.ROOT,
                "checksum %d x3 OK | LOD0 %d verts | %d tris | %d materials (2 drawn, eyes dropped)"
                        + " | %d anims, idle @stand_PRIMARY %d frames @ %.0f fps",
                mdl.checksum, model.vertices.size(), model.triangleCount,
                model.trianglesByMaterial.size(), animMdl.animDescs.length,
                idle.numFrames, idle.fps);
        SoldierMC.LOGGER.info("[soldiermc] Soldier model loaded: {}", summary);
    }

    private static void bindSeq(com.soldiermc.animstate.TfAnimState.Activity a, String name) {
        MdlFile.SeqDesc sd = animMdl.findSequence(name);
        if (sd == null) {
            SoldierMC.LOGGER.warn("[soldiermc] sequence {} missing, falling back to idle", name);
            return;
        }
        SEQS.put(a, sd);
    }

    /** The staging directory, or null if it cannot be found. Used by the audio pack builder. */
    public static Path stagingDir() {
        try {
            return locateStaging();
        } catch (IOException e) {
            return null;
        }
    }

    private static Path locateStaging() throws IOException {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path[] candidates = {
                gameDir.resolve("tf2-assets-staging"),
                gameDir.getParent() == null ? null : gameDir.getParent().resolve("tf2-assets-staging"),
        };
        for (Path p : candidates) {
            if (p != null && Files.isDirectory(p.resolve("models/player"))) return p;
        }
        throw new IOException("tf2-assets-staging/models/player not found near " + gameDir
                + " — extract with tools/extract-tf2-assets.ps1");
    }

    private static Identifier registerTexture(Path png, String name) throws IOException {
        try (InputStream in = Files.newInputStream(png)) {
            NativeImage img = NativeImage.read(in);
            Identifier id = Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, "dynamic/" + name);
            Minecraft.getInstance().getTextureManager().register(id, new DynamicTexture(() -> name, img));
            return id;
        }
    }
}
