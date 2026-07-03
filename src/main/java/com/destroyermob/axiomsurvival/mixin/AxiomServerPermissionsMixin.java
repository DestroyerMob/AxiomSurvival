package com.destroyermob.axiomsurvival.mixin;

import com.destroyermob.axiomsurvival.AxiomSurvivalPermissions;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.AxiomServer", remap = false)
public abstract class AxiomServerPermissionsMixin {
    @Inject(method = "getPermissions", at = @At("HEAD"), cancellable = true, remap = false)
    private static void axiomsurvival$grantSurvivalPermissions(ServerPlayerEntity player,
                                                               CallbackInfoReturnable<Object> callback) {
        Object permissions = AxiomSurvivalPermissions.survivalPermissionSet(player);
        if (permissions != null) {
            callback.setReturnValue(permissions);
        }
    }
}
