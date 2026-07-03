package com.destroyermob.axiomsurvival;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public final class AxiomSurvivalEdits {
    private static final int MAX_COST_LINES = 8;
    private static BlockState axiomBlockBufferEmptyState;
    private static boolean triedLoadingAxiomBlockBufferEmptyState;

    private AxiomSurvivalEdits() {
    }

    public static boolean stageSetBlock(ServerPlayerEntity player, Map<BlockPos, BlockState> blocks, int sequenceId) {
        if (!AxiomSurvivalConfig.captureEnabled() || !canUseAxiom(player, "BUILD_PLACE")) {
            return false;
        }
        acknowledgeSequence(player, sequenceId);
        PendingEdit edit = captureBlocks(player.getServerWorld(), blocks);
        applyCapturedEdit(player, player.getServerWorld(), edit);
        return true;
    }

    public static boolean stageBlockBuffer(Object blockBuffer, ServerWorld world, ServerPlayerEntity player) {
        if (!AxiomSurvivalConfig.captureEnabled() || player == null) {
            return false;
        }

        try {
            PendingEdit edit = captureBlockBuffer(blockBuffer, world);
            applyCapturedEdit(player, world, edit);
        } catch (ReflectiveOperationException exception) {
            AxiomSurvival.LOGGER.warn("Could not inspect Axiom block buffer; cancelling the edit to avoid free world edits.", exception);
            player.sendMessage(Text.literal("Could not inspect that Axiom edit, so it was cancelled.").formatted(Formatting.RED));
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

    private static PendingEdit captureBlocks(ServerWorld world, Map<BlockPos, BlockState> blocks) {
        PendingEdit edit = new PendingEdit();
        for (Map.Entry<BlockPos, BlockState> entry : blocks.entrySet()) {
            addBlockToEdit(world, edit, entry.getKey(), entry.getValue());
        }
        return edit;
    }

    private static PendingEdit captureBlockBuffer(Object blockBuffer, ServerWorld world) throws ReflectiveOperationException {
        PendingEdit edit = new PendingEdit();
        ClassLoader classLoader = blockBuffer.getClass().getClassLoader();
        Class<?> consumerClass = Class.forName("com.moulberry.axiom.collections.PositionConsumer", false, classLoader);
        BlockState emptyState = axiomBlockBufferEmptyState(blockBuffer);
        InvocationHandler handler = (proxy, method, args) -> {
            if ("accept".equals(method.getName())
                    && args != null
                    && args.length == 4
                    && args[0] instanceof Integer x
                    && args[1] instanceof Integer y
                    && args[2] instanceof Integer z
                    && args[3] instanceof BlockState state
                    && state != emptyState) {
                addBlockToEdit(world, edit, new BlockPos(x, y, z), state);
            }
            return null;
        };
        Object consumer = Proxy.newProxyInstance(classLoader, new Class<?>[]{consumerClass}, handler);
        Method forEach = blockBuffer.getClass().getMethod("forEach", consumerClass);
        forEach.invoke(blockBuffer, consumer);
        return edit;
    }

    private static boolean addBlockToEdit(ServerWorld world, PendingEdit edit, BlockPos pos, BlockState state) {
        int maxBlocks = AxiomSurvivalConfig.maxPendingBlocks();
        BlockPos immutablePos = pos.toImmutable();
        if (world.getBlockState(immutablePos).equals(state)) {
            edit.blocks.remove(immutablePos);
            return false;
        }

        boolean alreadyPresent = edit.blocks.containsKey(immutablePos);
        if (!alreadyPresent && edit.blocks.size() >= maxBlocks) {
            edit.overflowedBlocks++;
            return false;
        }

        edit.blocks.put(immutablePos, state);
        return !alreadyPresent;
    }

    private static void applyCapturedEdit(ServerPlayerEntity player, ServerWorld world, PendingEdit edit) {
        if (edit.blocks.isEmpty()) {
            player.sendMessage(Text.literal("Axiom edit had no block changes."), true);
            return;
        }
        if (edit.overflowedBlocks > 0) {
            player.sendMessage(Text.literal("Axiom edit is too large for survival capture; nothing was changed. Limit: "
                    + AxiomSurvivalConfig.maxPendingBlocks() + " block(s).").formatted(Formatting.RED), false);
            return;
        }

        CostReport report = costReport(player, world, edit);
        if (!report.unsupported.isEmpty()) {
            player.sendMessage(Text.literal("Axiom edit cancelled: some blocks have no survival item form. Examples: "
                    + unsupportedExamples(report.unsupported)).formatted(Formatting.RED), false);
            return;
        }
        if (!player.getAbilities().creativeMode && !report.missing.isEmpty()) {
            player.sendMessage(Text.literal("Axiom edit cancelled: missing " + missingSummary(report.missing) + ".")
                    .formatted(Formatting.RED), false);
            return;
        }

        if (!player.getAbilities().creativeMode) {
            removeCost(player, report.costs);
            returnBuckets(player, report.costs);
        }

        int applied = applyBlocks(world, edit);
        player.sendMessage(Text.literal("Applied " + applied + " Axiom block(s)."), true);
    }

    private static int applyBlocks(ServerWorld world, PendingEdit edit) {
        int applied = 0;
        for (Map.Entry<BlockPos, BlockState> entry : edit.blocks.entrySet()) {
            if (world.getBlockState(entry.getKey()).equals(entry.getValue())) {
                continue;
            }
            if (world.setBlockState(entry.getKey(), entry.getValue(), Block.NOTIFY_ALL)) {
                applied++;
            }
        }
        return applied;
    }

    private static BlockState axiomBlockBufferEmptyState(Object blockBuffer) {
        if (triedLoadingAxiomBlockBufferEmptyState) {
            return axiomBlockBufferEmptyState;
        }
        triedLoadingAxiomBlockBufferEmptyState = true;
        try {
            Field field = blockBuffer.getClass().getField("EMPTY_STATE");
            Object value = field.get(null);
            if (value instanceof BlockState state) {
                axiomBlockBufferEmptyState = state;
            }
        } catch (ReflectiveOperationException exception) {
            AxiomSurvival.LOGGER.warn("Could not inspect Axiom's block-buffer empty state; staged edits may include placeholder blocks.", exception);
        }
        return axiomBlockBufferEmptyState;
    }

    private static CostReport costReport(ServerPlayerEntity player, ServerWorld world, PendingEdit edit) {
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
        private final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        private int overflowedBlocks;
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
