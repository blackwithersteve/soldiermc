package com.soldiermc.mixin.client;

import com.soldiermc.client.SoldierMovement;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Cancels Minecraft's attack and use-item entry points, since the weapon reads M1/M2 passively. */
@Mixin(Minecraft.class)
public abstract class AttackSuppressMixin {

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void soldiermc$noStartAttack(CallbackInfoReturnable<Boolean> cir) {
        if (SoldierMovement.isActive()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void soldiermc$noContinueAttack(boolean leftClickHeld, CallbackInfo ci) {
        if (SoldierMovement.isActive()) {
            ci.cancel();
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void soldiermc$noUseItem(CallbackInfo ci) {
        if (SoldierMovement.isActive()) {
            ci.cancel();
        }
    }
}
