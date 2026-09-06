package com.soldiermc.weapon;

import java.util.Map;

import com.soldiermc.SoldierMC;
import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resources.FileToIdConverter;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads data/&lt;namespace&gt;/weapons/*.json. Registered against SERVER_DATA, so /reload re-reads
 * every number without restarting the game; a missing or malformed file is not fatal, since get()
 * falls back to the compiled-in stock values.
 */
public final class WeaponStatsLoader extends SimpleJsonResourceReloadListener<WeaponStats>
    implements IdentifiableResourceReloadListener {

    public static final Identifier ROCKET_LAUNCHER =
        Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, "rocket_launcher");

    private static final Identifier LISTENER_ID =
        Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, "weapons");

    private static Map<Identifier, WeaponStats> loaded = Map.of();

    private WeaponStatsLoader() {
        super(WeaponStats.CODEC, FileToIdConverter.json("weapons"));
    }

    public static void init() {
        ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(new WeaponStatsLoader());
    }

    /** Never null: falls back to the compiled-in stock values. */
    public static WeaponStats get(Identifier id) {
        final WeaponStats stats = loaded.get(id);
        return stats != null ? stats : WeaponStats.ROCKET_LAUNCHER_FALLBACK;
    }

    public static WeaponStats rocketLauncher() {
        return get(ROCKET_LAUNCHER);
    }

    @Override
    protected void apply(Map<Identifier, WeaponStats> parsed, ResourceManager manager,
                         ProfilerFiller profiler) {
        loaded = Map.copyOf(parsed);
        SoldierMC.LOGGER.info("loaded {} weapon stat file(s)", loaded.size());
    }

    @Override
    public Identifier getFabricId() {
        return LISTENER_ID;
    }
}
