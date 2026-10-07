package com.soldiermc;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;

/** Clients cannot read gamerules, so these only gate server-side effects. */
public final class SoldierRules {

    public static GameRule<Boolean> ROCKET_GRIEFING;

    private SoldierRules() {
    }

    public static boolean rocketGriefing(ServerLevel level) {
        return level.getGameRules().get(ROCKET_GRIEFING);
    }

    public static void init() {
        ROCKET_GRIEFING = GameRuleBuilder.forBoolean(false)
            .category(GameRuleCategory.PLAYER)
            .buildAndRegister(Identifier.fromNamespaceAndPath(SoldierMC.MOD_ID, "rocket_griefing"));
    }
}
