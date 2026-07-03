package com.destroyermob.axiomsurvival.mixin;

import com.destroyermob.axiomsurvival.AxiomSurvivalClientState;
import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public abstract class MinecraftClientAxiomSlotMixin {
    @Inject(method = "tick", at = @At("TAIL"))
    private void axiomsurvival$keepAxiomBuilderToolSlotVisible(CallbackInfo callback) {
        AxiomSurvivalClientState.keepBuilderToolSlotVisible((MinecraftClient) (Object) this);
    }
}
