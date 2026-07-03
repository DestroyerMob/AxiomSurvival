package com.destroyermob.axiomsurvival.mixin;

import com.destroyermob.axiomsurvival.AxiomSurvivalEdits;
import java.util.Map;
import net.minecraft.block.BlockState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.moulberry.axiom.packets.AxiomServerboundSetBlock", remap = false)
public abstract class AxiomSetBlockCaptureMixin {
    @Shadow(remap = false)
    @Final
    private Map<BlockPos, BlockState> blocks;

    @Shadow(remap = false)
    @Final
    private int sequenceId;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false)
    private void axiomsurvival$captureAxiomSetBlock(MinecraftServer server, ServerPlayerEntity player, CallbackInfo callback) {
        if (AxiomSurvivalEdits.stageSetBlock(player, blocks, sequenceId)) {
            callback.cancel();
        }
    }
}
