package com.soldiermc;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Concepts with no files are silent for the Soldier in TF2's talker rules. */
public final class SoldierVoiceLines {

    public static final class Concept {
        public final String name;
        public final String label;
        public final String[] files;
        /** Scene duration, which TF2's rate limiter uses instead of the audio length. */
        public final int sceneMs;
        public String gesture;
        public float gestureSeconds;
        public final List<String> bag = new ArrayList<>();

        Concept(String name, String label, int sceneMs, String... files) {
            this.name = name;
            this.label = label;
            this.sceneMs = sceneMs;
            this.files = files;
        }

        public boolean silent() {
            return files.length == 0;
        }
    }

    private static final Map<String, SoundEvent> EVENTS = new LinkedHashMap<>();

    private static Concept g(Concept c, String sequence, float seconds) {
        c.gesture = sequence;
        c.gestureSeconds = seconds;
        return c;
    }

    // Gesture seconds passed to g() are each sequence's length at 30 fps.
    private static final String FINGERPOINT = "gesture_primary_go";
    private static final String HANDMOUTH = "gesture_primary_help";
    private static final String FISTPUMP = "gesture_primary_cheer";
    private static final String THUMBSUP = "gesture_primary_positive";

    private static String[] seq(String stem, int n) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = String.format("soldier_%s%02d", stem, i + 1);
        return out;
    }

    public static final Concept[] MENU_1 = {
        g(new Concept("TLK_PLAYER_MEDIC", "MEDIC!", 1792, seq("medic", 3)), HANDMOUTH, 1.1667f),
        g(new Concept("TLK_PLAYER_THANKS", "Thanks!", 974, seq("thanks", 2)), THUMBSUP, 1.0f),
        g(new Concept("TLK_PLAYER_GO", "Go! Go! Go!", 1618, seq("go", 3)), FINGERPOINT, 1.0f),
        g(new Concept("TLK_PLAYER_MOVEUP", "Move Up!", 1250, seq("moveup", 3)), FINGERPOINT, 1.0f),
        g(new Concept("TLK_PLAYER_LEFT", "Go Left", 1791, seq("headleft", 3)), FINGERPOINT, 1.0f),
        g(new Concept("TLK_PLAYER_RIGHT", "Go Right", 2023, seq("headright", 3)), FINGERPOINT, 1.0f),
        new Concept("TLK_PLAYER_YES", "Yes", 1212, seq("yes", 4)),
        new Concept("TLK_PLAYER_NO", "No", 1397, seq("no", 3)),
        new Concept("TLK_PLAYER_ASK_FOR_BALL", "Pass to me!", 0),
    };

    public static final Concept[] MENU_2 = {
        g(new Concept("TLK_PLAYER_INCOMING", "Incoming", 1129, seq("incoming", 1)), HANDMOUTH, 1.1667f),
        new Concept("TLK_PLAYER_CLOAKEDSPY", "Spy!", 1968, seq("cloakedspy", 3)),
        g(new Concept("TLK_PLAYER_SENTRYAHEAD", "Sentry Ahead!", 1559, seq("sentryahead", 3)), FINGERPOINT, 1.0f),
        new Concept("TLK_PLAYER_TELEPORTERHERE", "Teleporter Here", 1806, seq("needteleporter", 1)),
        new Concept("TLK_PLAYER_DISPENSERHERE", "Dispenser Here", 1684, seq("needdispenser", 1)),
        new Concept("TLK_PLAYER_SENTRYHERE", "Sentry Here", 1568, seq("needsentry", 1)),
        new Concept("TLK_PLAYER_ACTIVATECHARGE", "Activate Charge!", 4719, seq("activatecharge", 3)),
        new Concept("TLK_PLAYER_CHARGEREADY", "ÜberCharge Ready", 0),
        new Concept("TLK_PLAYER_ASK_FOR_BALL", "Pass to me!", 0),
    };

    /** Eight entries, not nine, as in TF2. */
    public static final Concept[] MENU_3 = {
        g(new Concept("TLK_PLAYER_HELP", "Help!", 1700, seq("helpme", 3)), HANDMOUTH, 1.1667f),
        g(new Concept("TLK_PLAYER_BATTLECRY", "Battle Cry", 2252, seq("battlecry", 6)), FISTPUMP, 1.2667f),
        g(new Concept("TLK_PLAYER_CHEERS", "Cheers", 2508, seq("cheers", 6)), FISTPUMP, 1.2667f),
        new Concept("TLK_PLAYER_JEERS", "Jeers", 5671, seq("jeers", 12)),
        new Concept("TLK_PLAYER_POSITIVE", "Positive", 6049, merge(
            new String[]{"soldier_laughlong02"}, seq("laughshort", 4), seq("positivevocalization", 5))),
        new Concept("TLK_PLAYER_NEGATIVE", "Negative", 2128, seq("negativevocalization", 6)),
        g(new Concept("TLK_PLAYER_NICESHOT", "Nice Shot", 2902, seq("niceshot", 3)), THUMBSUP, 1.0f),
        g(new Concept("TLK_PLAYER_GOODJOB", "Good Job", 4063, seq("goodjob", 3)), THUMBSUP, 1.0f),
    };

    public static final Concept[][] MENUS = {MENU_1, MENU_2, MENU_3};

    public static final Concept PAIN = new Concept("ConceptPain", "pain", 0, seq("painsharp", 8));

    /** What the attacker hears in place of PAIN. */
    public static final Concept ATTACKER_PAIN =
        new Concept("ConceptAttackerPain", "attacker pain", 0, seq("painsevere", 6));

    public static final Concept DEATH_BLAST =
        new Concept("Soldier.ExplosionDeath", "blast death", 0, seq("painsharp", 8));
    public static final Concept DEATH_CRIT =
        new Concept("Soldier.CritDeath", "crit death", 0, seq("paincrticialdeath", 4));
    public static final Concept DEATH_GENERIC =
        new Concept("Soldier.Death", "death", 0, seq("painsevere", 6));

    public static final Concept ON_FIRE =
        new Concept("ConceptFire", "on fire", 0, seq("autoonfire", 3));

    public static final Concept ROUND_START =
        new Concept("ConceptPlayerRoundStart", "round start", 0, seq("battlecry", 6));

    private static final Concept[] REACTIONS = {
        PAIN, ATTACKER_PAIN, DEATH_BLAST, DEATH_CRIT, DEATH_GENERIC, ON_FIRE, ROUND_START,
    };

    public static SoundEvent BLAST_JUMP_WHISTLE;

    private SoldierVoiceLines() {
    }

    private static String[] merge(String[]... parts) {
        List<String> out = new ArrayList<>();
        for (String[] p : parts) out.addAll(List.of(p));
        return out.toArray(new String[0]);
    }

    public static List<String> allFiles() {
        List<String> out = new ArrayList<>();
        for (Concept[] menu : MENUS) {
            for (Concept c : menu) {
                for (String f : c.files) if (!out.contains(f)) out.add(f);
            }
        }
        for (Concept c : REACTIONS) {
            for (String f : c.files) if (!out.contains(f)) out.add(f);
        }
        return out;
    }

    public static void register() {
        for (String file : allFiles()) {
            Identifier id = Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, "vo." + file);
            EVENTS.put(file, Registry.register(BuiltInRegistries.SOUND_EVENT, id,
                    SoundEvent.createVariableRangeEvent(id)));
        }
        Identifier whistle = Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, "blastjump.whistle");
        BLAST_JUMP_WHISTLE = Registry.register(BuiltInRegistries.SOUND_EVENT, whistle,
                SoundEvent.createVariableRangeEvent(whistle));
    }

    public static SoundEvent event(String file) {
        return EVENTS.get(file);
    }
}
