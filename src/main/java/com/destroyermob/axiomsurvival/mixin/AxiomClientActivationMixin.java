package com.destroyermob.axiomsurvival.mixin;

import com.destroyermob.axiomsurvival.AxiomSurvivalClientState;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.AxiomClient", remap = false)
public abstract class AxiomClientActivationMixin {
    @Inject(method = "isAxiomActive(Lnet/minecraft/class_1934;)Z", at = @At("RETURN"), cancellable = true, remap = false)
    private static void axiomsurvival$allowSurvivalActivation(GameMode requestedMode,
                                                              CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValue() && AxiomSurvivalClientState.shouldTreatSurvivalAsAxiomActive(requestedMode)) {
            callback.setReturnValue(true);
        }
    }
}
