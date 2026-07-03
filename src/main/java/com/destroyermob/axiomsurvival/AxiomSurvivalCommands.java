package com.destroyermob.axiomsurvival;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

public final class AxiomSurvivalCommands {
    private AxiomSurvivalCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        registerRoot(dispatcher, "vanillaedit");
        registerRoot(dispatcher, "axiomsurvival");
    }

    private static void registerRoot(CommandDispatcher<ServerCommandSource> dispatcher, String name) {
        dispatcher.register(CommandManager.literal(name)
                .executes(context -> AxiomSurvivalEdits.status(context.getSource()))
                .then(CommandManager.literal("status")
                        .executes(context -> AxiomSurvivalEdits.status(context.getSource())))
                .then(CommandManager.literal("apply")
                        .executes(context -> AxiomSurvivalEdits.apply(context.getSource())))
                .then(CommandManager.literal("cancel")
                        .executes(context -> AxiomSurvivalEdits.cancel(context.getSource()))));
    }
}
