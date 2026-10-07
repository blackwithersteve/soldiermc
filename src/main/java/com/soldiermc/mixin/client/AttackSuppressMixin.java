package com.soldiermc.mixin.client;

import com.soldiermc.client.SoldierMovement;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** The weapon polls the attack and use keys, so vanilla's handlers are cancelled. */
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
