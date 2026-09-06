package com.soldiermc.mixin.client;

import com.soldiermc.SoldierMC;
import com.soldiermc.client.SoldierMovement;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Cancels {@code Player.travel} for the local player so the ported Source simulation moves him. */
@Mixin(Player.class)
public abstract class PlayerTravelMixin {

    private static boolean soldiermc$announced;

    @Inject(method = "travel(Lnet/minecraft/world/phys/Vec3;)V", at = @At("HEAD"), cancellable = true)
    private void soldiermc$sourceTravel(Vec3 input, CallbackInfo ci) {
        if (!((Object) this instanceof LocalPlayer local)) return;

        // Bail out to vanilla for states the port does not model.
        if (local.isSpectator() || local.isPassenger() || local.getAbilities().flying
                || local.isInWater() || local.isInLava() || local.onClimbable()) {
            return;
        }

        if (SoldierMovement.travel(local)) {
            if (!soldiermc$announced) {
                soldiermc$announced = true;
                SoldierMC.LOGGER.info(
                        "PlayerTravelMixin applied - Source physics has the local player");
            }
            ci.cancel();
        }
    }
}
