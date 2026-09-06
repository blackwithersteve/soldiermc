package com.soldiermc;

import com.soldiermc.weapon.WeaponStatsLoader;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Common entrypoint. Movement is client-side; the weapon data loads on both sides. */
public class SoldierMC implements ModInitializer {

    public static final String MOD_ID = "soldiermc";

    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        SoldierRules.init();
        WeaponStatsLoader.init();
        SoldierSoundEvents.register();
        SoldierVoiceLines.register();
    }
}
