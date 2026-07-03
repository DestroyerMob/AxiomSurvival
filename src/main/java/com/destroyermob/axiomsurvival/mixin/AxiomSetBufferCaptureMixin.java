package com.destroyermob.axiomsurvival.mixin;

import com.destroyermob.axiomsurvival.AxiomSurvivalEdits;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBuffer", remap = false)
public abstract class AxiomSetBufferCaptureMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void axiomsurvival$cancelUnsupportedAxiomBuffer(MinecraftServer server, ServerPlayerEntity player, CallbackInfo callback) {
        if (AxiomSurvivalEdits.cancelUnsupportedSetBuffer(this, player)) {
            callback.cancel();
        }
    }

    @Inject(method = "applyBlockBufferServer", at = @At("HEAD"), cancellable = true, remap = false)
    private static void axiomsurvival$captureAxiomBlockBuffer(
            @Coerce Object blockBuffer,
            ServerWorld world,
            @Coerce Object changedRegion,
            ServerPlayerEntity player,
            CallbackInfo callback) {
        if (AxiomSurvivalEdits.stageBlockBuffer(blockBuffer, world, player)) {
            callback.cancel();
        }
    }
}
