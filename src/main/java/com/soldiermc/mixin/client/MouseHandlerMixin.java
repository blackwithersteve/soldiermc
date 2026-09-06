package com.soldiermc.mixin.client;

import com.soldiermc.SoldierMC;
import com.soldiermc.client.SoldierMovement;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Samples input in {@code handleAccumulatedMovement}, once per frame: air-strafe gain follows R. */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    private static boolean soldiermc$announced;

    @Inject(method = "handleAccumulatedMovement", at = @At("TAIL"))
    private void soldiermc$sampleFrame(CallbackInfo ci) {
        if (!SoldierMovement.isActive()) return;

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return;

        SoldierMovement.sampleFrame(player);

        if (!soldiermc$announced) {
            soldiermc$announced = true;
            SoldierMC.LOGGER.info(
                    "MouseHandlerMixin applied - per-frame input sampling is live");
        }
    }
}
