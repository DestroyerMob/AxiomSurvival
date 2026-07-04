package com.destroyermob.axiomsurvival.mixin;

import com.destroyermob.axiomsurvival.AxiomSurvivalInteractionState;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.capabilities.Tinker", remap = false)
public abstract class AxiomTinkerInteractionMixin {
    @Inject(method = "performUseItemOn(Lnet/minecraft/class_746;Lnet/minecraft/class_1268;Lnet/minecraft/class_3965;)Lnet/minecraft/class_1269;",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void axiomsurvival$letVanillaUseBlocks(ClientPlayerEntity player, Hand hand, BlockHitResult hit,
                                                          CallbackInfoReturnable<ActionResult> callback) {
        if (AxiomSurvivalInteractionState.shouldBypassAxiomWorldInteraction()) {
            callback.setReturnValue(ActionResult.PASS);
        }
    }

    @Inject(method = "startDestroyBlockCreative(Lnet/minecraft/class_3965;)Z",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void axiomsurvival$letVanillaAttackBlocks(BlockHitResult hit,
                                                             CallbackInfoReturnable<Boolean> callback) {
        if (AxiomSurvivalInteractionState.shouldBypassAxiomWorldInteraction()) {
            callback.setReturnValue(false);
        }
    }
}
