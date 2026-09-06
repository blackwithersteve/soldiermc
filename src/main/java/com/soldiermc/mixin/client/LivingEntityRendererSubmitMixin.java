package com.soldiermc.mixin.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.soldiermc.SoldierMC;
import com.soldiermc.client.SoldierBody;
import com.soldiermc.client.SoldierMovement;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hooks {@code LivingEntityRenderer.submit} to draw the Soldier mesh instead of the player model. */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererSubmitMixin {

    private static boolean soldiermc$announced;

    @Inject(method = "submit(Lnet/minecraft/client/renderer/entity/state/LivingEntityRenderState;"
                   + "Lcom/mojang/blaze3d/vertex/PoseStack;"
                   + "Lnet/minecraft/client/renderer/SubmitNodeCollector;"
                   + "Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
            at = @At("HEAD"), cancellable = true)
    private void soldiermc$submitSoldier(LivingEntityRenderState state, PoseStack pose,
                                         SubmitNodeCollector collector, CameraRenderState camera,
                                         CallbackInfo ci) {
        if (!(state instanceof AvatarRenderState avatar)) return;
        if (!SoldierMovement.isActive()) return;

        if (SoldierBody.submit(avatar, pose, collector)) {
            if (!soldiermc$announced) {
                soldiermc$announced = true;
                SoldierMC.LOGGER.info(
                        "LivingEntityRendererSubmitMixin applied - the Soldier mesh is rendering");
            }
            ci.cancel();
        }
    }
}
