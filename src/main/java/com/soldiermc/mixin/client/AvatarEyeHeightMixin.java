package com.soldiermc.mixin.client;

import com.soldiermc.client.SoldierMovement;
import com.soldiermc.source.SourceFeel;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Hooks {@code Avatar.getDefaultDimensions} to put the camera at TF2's 68 hu eye height. */
@Mixin(Avatar.class)
public abstract class AvatarEyeHeightMixin {

    @Inject(method = "getDefaultDimensions", at = @At("RETURN"), cancellable = true)
    private void soldiermc$tf2EyeHeight(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        if (!SoldierMovement.isActive()) return;
        if ((Object) this != Minecraft.getInstance().player) return;

        float eyeHu = (pose == Pose.CROUCHING)
                ? SourceFeel.VIEW_HEIGHT_DUCKED
                : SourceFeel.VIEW_HEIGHT_STANDING;

        cir.setReturnValue(cir.getReturnValue()
                .withEyeHeight(eyeHu / SourceFeel.UNITS_PER_BLOCK));
    }
}
