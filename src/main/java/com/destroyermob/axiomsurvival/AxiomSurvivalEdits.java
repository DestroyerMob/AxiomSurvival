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
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public final class AxiomSurvivalEdits {
    private static final int MAX_COST_LINES = 8;
    private static final int UNLIMITED_TOOL_USES = Integer.MAX_VALUE / 4;
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
        if (!player.getAbilities().creativeMode && !report.unbreakable.isEmpty()) {
            player.sendMessage(Text.literal("Axiom edit cancelled: some source blocks cannot be broken in survival. Examples: "
                    + blockExamples(report.unbreakable)).formatted(Formatting.RED), false);
            return;
        }
        if (!player.getAbilities().creativeMode && !report.missingTools.isEmpty()) {
            player.sendMessage(Text.literal("Axiom edit cancelled: missing suitable tool for "
                    + missingToolSummary(report.missingTools) + ".").formatted(Formatting.RED), false);
            return;
        }
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
            damageTools(player, world, report.toolUses);
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
        Map<Item, Integer> materialCredits = new LinkedHashMap<>();
        Map<Integer, Integer> remainingToolUses = new LinkedHashMap<>();
        Map<Integer, Integer> toolUses = new LinkedHashMap<>();
        List<BlockPos> unsupported = new ArrayList<>();
        List<BreakRequirement> unbreakable = new ArrayList<>();
        List<BreakRequirement> missingTools = new ArrayList<>();
        int unchanged = 0;
        int removals = 0;
        int reusedMaterials = 0;

        for (Map.Entry<BlockPos, BlockState> entry : edit.blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState source = world.getBlockState(pos);
            BlockState target = entry.getValue();
            if (source.equals(target)) {
                unchanged++;
                continue;
            }

            if (!source.isAir()) {
                removals++;
                Item sourceItem = survivalCostItem(source);
                if (sourceItem != Items.AIR) {
                    materialCredits.merge(sourceItem, 1, Integer::sum);
                }
                validateBreak(player, world, pos, source, remainingToolUses, toolUses, unbreakable, missingTools);
            }
        }

        for (Map.Entry<BlockPos, BlockState> entry : edit.blocks.entrySet()) {
            BlockState source = world.getBlockState(entry.getKey());
            BlockState target = entry.getValue();
            if (source.equals(target) || target.isAir()) {
                continue;
            }
            Item item = survivalCostItem(target);
            if (item == Items.AIR) {
                unsupported.add(entry.getKey());
                continue;
            }
            if (consumeMaterialCredit(materialCredits, item)) {
                reusedMaterials++;
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

        return new CostReport(costs, missing, unsupported, unbreakable, missingTools, toolUses, unchanged, removals, reusedMaterials);
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

    private static void validateBreak(
            ServerPlayerEntity player,
            ServerWorld world,
            BlockPos pos,
            BlockState source,
            Map<Integer, Integer> remainingToolUses,
            Map<Integer, Integer> toolUses,
            List<BreakRequirement> unbreakable,
            List<BreakRequirement> missingTools) {
        if (player.getAbilities().creativeMode) {
            return;
        }
        if (source.getHardness(world, pos) < 0.0F) {
            unbreakable.add(new BreakRequirement(source, pos));
            return;
        }
        if (!requiresToolForAxiomEdit(source)) {
            return;
        }

        int slot = findSuitableToolSlot(player, source, remainingToolUses);
        if (slot < 0) {
            missingTools.add(new BreakRequirement(source, pos));
            return;
        }

        int remaining = remainingToolUses.get(slot);
        if (remaining < UNLIMITED_TOOL_USES) {
            remainingToolUses.put(slot, remaining - 1);
        }
        toolUses.merge(slot, 1, Integer::sum);
    }

    private static int findSuitableToolSlot(
            ServerPlayerEntity player,
            BlockState source,
            Map<Integer, Integer> remainingToolUses) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.isEmpty() || !isSuitableToolForAxiomEdit(stack, source)) {
                continue;
            }
            int remaining = remainingToolUses.computeIfAbsent(slot, ignored -> remainingToolUses(stack));
            if (remaining > 0) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean requiresToolForAxiomEdit(BlockState source) {
        return source.isToolRequired() || hasPreferredToolTag(source);
    }

    private static boolean isSuitableToolForAxiomEdit(ItemStack stack, BlockState source) {
        if (source.isToolRequired() && !stack.isSuitableFor(source)) {
            return false;
        }
        return !hasPreferredToolTag(source) || stack.getMiningSpeedMultiplier(source) > 1.0F;
    }

    private static boolean hasPreferredToolTag(BlockState source) {
        return source.isIn(BlockTags.AXE_MINEABLE)
                || source.isIn(BlockTags.HOE_MINEABLE)
                || source.isIn(BlockTags.PICKAXE_MINEABLE)
                || source.isIn(BlockTags.SHOVEL_MINEABLE)
                || source.isIn(BlockTags.SWORD_EFFICIENT);
    }

    private static int remainingToolUses(ItemStack stack) {
        if (!stack.isDamageable()) {
            return UNLIMITED_TOOL_USES;
        }
        return Math.max(0, stack.getMaxDamage() - stack.getDamage());
    }

    private static boolean consumeMaterialCredit(Map<Item, Integer> materialCredits, Item item) {
        int available = materialCredits.getOrDefault(item, 0);
        if (available <= 0) {
            return false;
        }
        if (available == 1) {
            materialCredits.remove(item);
        } else {
            materialCredits.put(item, available - 1);
        }
        return true;
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

    private static void damageTools(ServerPlayerEntity player, ServerWorld world, Map<Integer, Integer> toolUses) {
        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<Integer, Integer> entry : toolUses.entrySet()) {
            ItemStack stack = inventory.getStack(entry.getKey());
            for (int use = 0; use < entry.getValue() && !stack.isEmpty(); use++) {
                stack.damage(1, world, player, item -> {
                });
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

    private static String missingToolSummary(List<BreakRequirement> missingTools) {
        return missingTools.stream()
                .limit(MAX_COST_LINES)
                .map(requirement -> blockName(requirement.state) + " at " + requirement.pos.toShortString())
                .reduce((left, right) -> left + ", " + right)
                .orElse("nothing")
                + (missingTools.size() > MAX_COST_LINES ? ", ..." : "");
    }

    private static String blockExamples(List<BreakRequirement> blocks) {
        return blocks.stream()
                .limit(MAX_COST_LINES)
                .map(requirement -> blockName(requirement.state) + " at " + requirement.pos.toShortString())
                .reduce((left, right) -> left + ", " + right)
                .orElse("none")
                + (blocks.size() > MAX_COST_LINES ? ", ..." : "");
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

    private static String blockName(BlockState state) {
        return state.getBlock().getName().getString();
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
            List<BreakRequirement> unbreakable,
            List<BreakRequirement> missingTools,
            Map<Integer, Integer> toolUses,
            int unchanged,
            int removals,
            int reusedMaterials) {
    }

    private record MissingCost(Item item, int required, int available) {
    }

    private record BreakRequirement(BlockState state, BlockPos pos) {
    }
}
