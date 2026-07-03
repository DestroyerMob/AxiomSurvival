package com.destroyermob.axiomsurvival;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public final class AxiomSurvivalEdits {
    private static final int MAX_COST_LINES = 8;
    private static final long NOTIFY_INTERVAL_TICKS = 40L;
    private static final Map<UUID, PendingEdit> PENDING_EDITS = new HashMap<>();

    private AxiomSurvivalEdits() {
    }

    public static boolean stageSetBlock(ServerPlayerEntity player, Map<BlockPos, BlockState> blocks, int sequenceId) {
        if (!AxiomSurvivalConfig.captureEnabled() || !canUseAxiom(player, "BUILD_PLACE")) {
            return false;
        }
        acknowledgeSequence(player, sequenceId);
        int staged = stageBlocks(player, player.getServerWorld(), blocks);
        notifyStaged(player, staged);
        return true;
    }

    public static boolean stageBlockBuffer(Object blockBuffer, ServerWorld world, ServerPlayerEntity player) {
        if (!AxiomSurvivalConfig.captureEnabled() || player == null) {
            return false;
        }

        try {
            ClassLoader classLoader = blockBuffer.getClass().getClassLoader();
            Class<?> consumerClass = Class.forName("com.moulberry.axiom.collections.PositionConsumer", false, classLoader);
            int[] staged = {0};
            InvocationHandler handler = (proxy, method, args) -> {
                if ("accept".equals(method.getName())
                        && args != null
                        && args.length == 4
                        && args[0] instanceof Integer x
                        && args[1] instanceof Integer y
                        && args[2] instanceof Integer z
                        && args[3] instanceof BlockState state) {
                    if (stageBlock(player, world, new BlockPos(x, y, z), state)) {
                        staged[0]++;
                    }
                }
                return null;
            };
            Object consumer = Proxy.newProxyInstance(classLoader, new Class<?>[]{consumerClass}, handler);
            Method forEach = blockBuffer.getClass().getMethod("forEach", consumerClass);
            forEach.invoke(blockBuffer, consumer);
            notifyStaged(player, staged[0]);
        } catch (ReflectiveOperationException exception) {
            AxiomSurvival.LOGGER.warn("Could not stage Axiom block buffer; cancelling the edit to avoid free world edits.", exception);
            player.sendMessage(Text.literal("Could not stage that Axiom edit, so it was cancelled.").formatted(Formatting.RED));
        }
        return true;
    }

    public static boolean cancelUnsupportedSetBuffer(Object packet, ServerPlayerEntity player) {
        if (!AxiomSurvivalConfig.captureEnabled() || player == null) {
            return false;
        }
        try {
            Field bufferField = packet.getClass().getDeclaredField("buffer");
            Object buffer = bufferField.trySetAccessible() ? bufferField.get(packet) : null;
            if (buffer != null && "com.moulberry.axiom.world_modification.BiomeBuffer".equals(buffer.getClass().getName())) {
                player.sendMessage(Text.literal("Axiom biome edits are disabled while vanilla edit capture is enabled.")
                        .formatted(Formatting.RED));
                return true;
            }
        } catch (ReflectiveOperationException exception) {
            AxiomSurvival.LOGGER.debug("Could not inspect Axiom set-buffer packet.", exception);
        }
        return false;
    }

    public static int status(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        PendingEdit edit = pending(player);
        if (edit == null || edit.blocks.isEmpty()) {
            source.sendFeedback(() -> Text.literal("No pending vanilla edit."), false);
            return 0;
        }
        if (!edit.dimension.equals(player.getServerWorld().getRegistryKey())) {
            source.sendFeedback(() -> Text.literal("Pending vanilla edit: " + edit.blocks.size()
                    + " staged block(s) in another dimension. Go back there to inspect/apply it, or use /vanillaedit cancel."), false);
            return edit.blocks.size();
        }

        CostReport report = costReport(player, edit);
        sendReport(source, edit, report);
        return edit.blocks.size();
    }

    public static int apply(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        PendingEdit edit = pending(player);
        if (edit == null || edit.blocks.isEmpty()) {
            source.sendError(Text.literal("No pending vanilla edit to apply."));
            return 0;
        }
        if (!edit.dimension.equals(player.getServerWorld().getRegistryKey())) {
            source.sendError(Text.literal("That pending edit belongs to another dimension. Go back there or cancel it."));
            return 0;
        }

        CostReport report = costReport(player, edit);
        if (!report.unsupported.isEmpty()) {
            source.sendError(Text.literal("This edit contains blocks with no survival item form. Cancel it or remove those blocks."));
            source.sendFeedback(() -> Text.literal("Unsupported examples: " + unsupportedExamples(report.unsupported)), false);
            return 0;
        }
        if (!player.getAbilities().creativeMode && !report.missing.isEmpty()) {
            source.sendError(Text.literal("You do not have enough materials for this edit."));
            source.sendFeedback(() -> Text.literal("Missing: " + missingSummary(report.missing)), false);
            return 0;
        }

        if (!player.getAbilities().creativeMode) {
            removeCost(player, report.costs);
            returnBuckets(player, report.costs);
        }

        ServerWorld world = player.getServerWorld();
        int applied = 0;
        for (Map.Entry<BlockPos, BlockState> entry : edit.blocks.entrySet()) {
            if (world.getBlockState(entry.getKey()).equals(entry.getValue())) {
                continue;
            }
            if (world.setBlockState(entry.getKey(), entry.getValue(), Block.NOTIFY_ALL)) {
                applied++;
            }
        }
        PENDING_EDITS.remove(player.getUuid());
        int finalApplied = applied;
        source.sendFeedback(() -> Text.literal("Applied " + finalApplied + " block(s) from the pending vanilla edit."), true);
        return applied;
    }

    public static int cancel(ServerCommandSource source) throws CommandSyntaxException {
        ServerPlayerEntity player = source.getPlayerOrThrow();
        PendingEdit removed = PENDING_EDITS.remove(player.getUuid());
        int count = removed == null ? 0 : removed.blocks.size();
        source.sendFeedback(() -> Text.literal("Cancelled pending vanilla edit with " + count + " staged block(s)."), false);
        return count;
    }

    private static int stageBlocks(ServerPlayerEntity player, ServerWorld world, Map<BlockPos, BlockState> blocks) {
        int staged = 0;
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            if (stageBlock(player, world, entry.getKey(), entry.getValue())) {
                staged++;
            }
        }
        return staged;
    }

    private static boolean stageBlock(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state) {
        PendingEdit edit = pendingOrCreate(player, world);
        if (!edit.dimension.equals(world.getRegistryKey())) {
            PENDING_EDITS.remove(player.getUuid());
            edit = pendingOrCreate(player, world);
            player.sendMessage(Text.literal("Started a new pending vanilla edit in this dimension."));
        }

        int maxBlocks = AxiomSurvivalConfig.maxPendingBlocks();
        BlockPos immutablePos = pos.toImmutable();
        boolean alreadyPresent = edit.blocks.containsKey(immutablePos);
        if (!alreadyPresent && edit.blocks.size() >= maxBlocks) {
            edit.overflowedBlocks++;
            if (edit.overflowedBlocks == 1) {
                player.sendMessage(Text.literal("Pending vanilla edit reached the configured limit of "
                        + maxBlocks + " blocks. Extra blocks are being ignored.").formatted(Formatting.RED));
            }
            return false;
        }

        edit.blocks.put(immutablePos, state);
        return !alreadyPresent;
    }

    private static void notifyStaged(ServerPlayerEntity player, int staged) {
        PendingEdit edit = pending(player);
        if (edit == null || staged <= 0) {
            return;
        }
        long gameTime = player.getServerWorld().getTime();
        if (gameTime - edit.lastNotifyGameTime < NOTIFY_INTERVAL_TICKS) {
            return;
        }
        edit.lastNotifyGameTime = gameTime;
        player.sendMessage(Text.literal("Axiom edit staged: " + edit.blocks.size()
                + " block(s). Use /vanillaedit apply or /vanillaedit cancel."), true);
    }

    private static PendingEdit pending(ServerPlayerEntity player) {
        return PENDING_EDITS.get(player.getUuid());
    }

    private static PendingEdit pendingOrCreate(ServerPlayerEntity player, ServerWorld world) {
        return PENDING_EDITS.computeIfAbsent(player.getUuid(), id -> new PendingEdit(world.getRegistryKey()));
    }

    private static CostReport costReport(ServerPlayerEntity player, PendingEdit edit) {
        ServerWorld world = player.getServerWorld();
        Map<Item, Integer> costs = new LinkedHashMap<>();
        List<BlockPos> unsupported = new ArrayList<>();
        int unchanged = 0;
        int removals = 0;

        for (Map.Entry<BlockPos, BlockState> entry : edit.blocks.entrySet()) {
            BlockState target = entry.getValue();
            if (world.getBlockState(entry.getKey()).equals(target)) {
                unchanged++;
                continue;
            }
            if (target.isAir()) {
                removals++;
                continue;
            }

            Item item = survivalCostItem(target);
            if (item == Items.AIR) {
                unsupported.add(entry.getKey());
                continue;
            }
            costs.merge(item, 1, Integer::sum);
        }

        List<MissingCost> missing = new ArrayList<>();
        if (!player.getAbilities().creativeMode) {
            for (Map.Entry<Item, Integer> entry : costs.entrySet()) {
                int available = countItem(player, entry.getKey());
                if (available < entry.getValue()) {
                    missing.add(new MissingCost(entry.getKey(), entry.getValue(), available));
                }
            }
        }

        return new CostReport(costs, missing, unsupported, unchanged, removals);
    }

    private static Item survivalCostItem(BlockState state) {
        if (state.isOf(Blocks.WATER)) {
            return Items.WATER_BUCKET;
        }
        if (state.isOf(Blocks.LAVA)) {
            return Items.LAVA_BUCKET;
        }
        if (state.isOf(Blocks.POWDER_SNOW)) {
            return Items.POWDER_SNOW_BUCKET;
        }
        return state.getBlock().asItem();
    }

    private static int countItem(ServerPlayerEntity player, Item item) {
        PlayerInventory inventory = player.getInventory();
        int count = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isOf(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static void removeCost(ServerPlayerEntity player, Map<Item, Integer> costs) {
        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<Item, Integer> entry : costs.entrySet()) {
            int remaining = entry.getValue();
            for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
                ItemStack stack = inventory.getStack(slot);
                if (!stack.isOf(entry.getKey())) {
                    continue;
                }
                int removed = Math.min(remaining, stack.getCount());
                stack.decrement(removed);
                remaining -= removed;
            }
        }
        inventory.markDirty();
    }

    private static void returnBuckets(ServerPlayerEntity player, Map<Item, Integer> costs) {
        int bucketCount = costs.getOrDefault(Items.WATER_BUCKET, 0)
                + costs.getOrDefault(Items.LAVA_BUCKET, 0)
                + costs.getOrDefault(Items.POWDER_SNOW_BUCKET, 0);
        while (bucketCount > 0) {
            int count = Math.min(bucketCount, 64);
            ItemStack buckets = new ItemStack(Items.BUCKET, count);
            player.getInventory().insertStack(buckets);
            if (!buckets.isEmpty()) {
                player.dropItem(buckets, false);
            }
            bucketCount -= count;
        }
    }

    private static void sendReport(ServerCommandSource source, PendingEdit edit, CostReport report) {
        source.sendFeedback(() -> Text.literal("Pending vanilla edit: " + edit.blocks.size()
                + " staged block(s), " + report.removals + " removal(s), " + report.unchanged + " unchanged."), false);
        if (edit.overflowedBlocks > 0) {
            source.sendFeedback(() -> Text.literal(edit.overflowedBlocks
                    + " block(s) were ignored after the pending edit limit was reached.").formatted(Formatting.RED), false);
        }
        if (report.costs.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Cost: no items required."), false);
        } else {
            source.sendFeedback(() -> Text.literal("Cost: " + costSummary(report.costs)), false);
        }
        if (!report.missing.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Missing: " + missingSummary(report.missing)).formatted(Formatting.RED), false);
        }
        if (!report.unsupported.isEmpty()) {
            source.sendFeedback(() -> Text.literal("Unsupported blocks: " + report.unsupported.size()
                    + " example(s): " + unsupportedExamples(report.unsupported)).formatted(Formatting.RED), false);
        }
    }

    private static String costSummary(Map<Item, Integer> costs) {
        return costs.entrySet().stream()
                .sorted(Comparator.comparing(entry -> itemName(entry.getKey())))
                .limit(MAX_COST_LINES)
                .map(entry -> entry.getValue() + " " + itemName(entry.getKey()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("no items required")
                + (costs.size() > MAX_COST_LINES ? ", ..." : "");
    }

    private static String missingSummary(List<MissingCost> missing) {
        return missing.stream()
                .limit(MAX_COST_LINES)
                .map(cost -> (cost.required - cost.available) + " " + itemName(cost.item))
                .reduce((left, right) -> left + ", " + right)
                .orElse("nothing")
                + (missing.size() > MAX_COST_LINES ? ", ..." : "");
    }

    private static String unsupportedExamples(List<BlockPos> unsupported) {
        return unsupported.stream()
                .limit(MAX_COST_LINES)
                .map(BlockPos::toShortString)
                .reduce((left, right) -> left + ", " + right)
                .orElse("none")
                + (unsupported.size() > MAX_COST_LINES ? ", ..." : "");
    }

    private static String itemName(Item item) {
        return new ItemStack(item).getName().getString();
    }

    private static boolean canUseAxiom(ServerPlayerEntity player, String permissionName) {
        try {
            Class<?> permissionClass = Class.forName("com.moulberry.axiom.restrictions.AxiomPermission");
            Object permission = permissionClass.getField(permissionName).get(null);
            Class<?> packetClass = Class.forName("com.moulberry.axiom.packets.AxiomServerboundPacket");
            Method method = packetClass.getMethod("canUseAxiom", ServerPlayerEntity.class, permissionClass);
            return Boolean.TRUE.equals(method.invoke(null, player, permission));
        } catch (ReflectiveOperationException exception) {
            AxiomSurvival.LOGGER.warn("Could not query Axiom permission {}; falling back to vanilla op permission.", permissionName, exception);
            return player.hasPermissionLevel(2);
        }
    }

    private static void acknowledgeSequence(ServerPlayerEntity player, int sequenceId) {
        if (sequenceId < 0) {
            return;
        }
        for (String methodName : List.of("acknowledgeBlockChangesUpTo", "ackBlockChangesUpTo", "method_41255")) {
            try {
                Method method = player.networkHandler.getClass().getMethod(methodName, int.class);
                method.invoke(player.networkHandler, sequenceId);
                return;
            } catch (ReflectiveOperationException ignored) {
                // Try the next mapping name.
            }
        }
        AxiomSurvival.LOGGER.debug("Could not acknowledge staged Axiom block sequence {}.", sequenceId);
    }

    private static final class PendingEdit {
        private final RegistryKey<World> dimension;
        private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        private long lastNotifyGameTime = Long.MIN_VALUE;
        private int overflowedBlocks;

        private PendingEdit(RegistryKey<World> dimension) {
            this.dimension = dimension;
        }
    }

    private record CostReport(
            Map<Item, Integer> costs,
            List<MissingCost> missing,
            List<BlockPos> unsupported,
            int unchanged,
            int removals) {
    }

    private record MissingCost(Item item, int required, int available) {
    }
}
