package com.soldiermc.mixin.client;

import com.soldiermc.client.SoldierBody;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Advances the animation state machine once per rendered frame, as {@code OnRenderStart} does. */
@Mixin(Minecraft.class)
public abstract class FrameAdvanceMixin {

    @Inject(method = "renderFrame(Z)V", at = @At("HEAD"))
    private void soldiermc$advanceAnimation(boolean tick, CallbackInfo ci) {
        SoldierBody.advance();
    }
}
