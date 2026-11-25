package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.FoodStatus;
import dev.stowling.client.interaction.ClientActionScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftFoodConsumptionPort
        implements FoodConsumptionPort {
    private static final int EATING_TIMEOUT_TICKS = 100;
    private static final int EATING_START_RETRIES = 4;
    private static final int EATING_RETRY_INTERVAL_TICKS = 3;
    private static final Duration CONTAINER_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TAKING_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration CLOSING_TIMEOUT = Duration.ofSeconds(2);
    private static final int CONFIRM_POLLS = 2;

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final dev.stowling.automation.FoodUseGuard useGuard;
    private ActiveEating active;
    private ActiveAcquiringFood acquiring;

    public MinecraftFoodConsumptionPort(
            Minecraft minecraft,
            ClientActionScheduler scheduler
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.useGuard = ((MinecraftFoodUseAccess) minecraft).stowling$foodUseGuard();
    }

    @Override
    public FoodStatus observe() {
        if (!scheduler.isClientThread()) {
            return FoodStatus.disconnected();
        }
        LocalPlayer player = minecraft.player;
        if (player == null || player.isRemoved()) {
            return FoodStatus.disconnected();
        }
        return new FoodStatus(
                true,
                player.getFoodData().getFoodLevel(),
                player.getFoodData().getSaturationLevel()
        );
    }

    @Override
    public FoodConsumptionResult startEating(String itemId) {
        return beginEating(itemId, null);
    }

    @Override
    public FoodConsumptionResult startEating(String itemId, Set<String> allowedKeys) {
        return beginEating(itemId, Set.copyOf(allowedKeys));
    }

    private FoodConsumptionResult beginEating(String itemId, Set<String> allowedKeys) {
        Objects.requireNonNull(itemId, "itemId");
        if (!scheduler.isClientThread()) {
            return FoodConsumptionResult.failed(
                    "自动进食必须在客户端线程执行"
            );
        }
        if (acquiring != null) {
            return FoodConsumptionResult.failed("正在从食物箱取出食物");
        }
        if (active != null) {
            return FoodConsumptionResult.failed("已有进食操作正在进行");
        }
        if (minecraft.gui.screen() != null) {
            return FoodConsumptionResult.failed("请先关闭当前界面");
        }
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null || player.isRemoved()) {
            return FoodConsumptionResult.failed("当前没有可进食的玩家");
        }
        int previousSelected = player.getInventory().getSelectedSlot();
        FoodSlot slot = findFoodSlot(player, itemId, allowedKeys);
        if (slot == null) {
            return FoodConsumptionResult.failed(
                    "背包中没有可用的指定食物: " + itemId
            );
        }
        swapFoodSlot(player, slot);
        ItemStack food = player.getInventory().getItem(slot.index());
        if (!foodMatches(food, itemId, allowedKeys)) {
            restoreFoodSlot(player, slot, previousSelected);
            return FoodConsumptionResult.failed("无法将指定食物放入使用槽");
        }
        String itemKey = MinecraftItemIdentity.key(food);
        int itemCountBefore = allowedKeys == null ? food.getCount() : playerItemCount(player,
                itemKey, slot.hand() == InteractionHand.OFF_HAND);
        int foodLevelBefore = player.getFoodData().getFoodLevel();
        if (slot.hotbar()) {
            player.getInventory().setSelectedSlot(slot.index());
            if (player.connection != null) {
                player.connection.send(new ServerboundSetCarriedItemPacket(
                        slot.index()
                ));
            }
        }
        boolean useKeyWasDown = minecraft.options.keyUse.isDown();
        if (!useKeyWasDown) {
            minecraft.options.keyUse.setDown(true);
        }
        InteractionResult result = gameMode.useItem(player, slot.hand());
        if (!result.consumesAction()) {
            if (!useKeyWasDown) {
                minecraft.options.keyUse.setDown(false);
            }
            restoreFoodSlot(player, slot, previousSelected);
            return FoodConsumptionResult.failed(
                    "无法使用指定食物: " + itemId
            );
        }
        active = new ActiveEating(
                itemId,
                itemKey,
                slot,
                previousSelected,
                foodLevelBefore,
                itemCountBefore,
                allowedKeys != null,
                !useKeyWasDown
        );
        useGuard.begin();
        return FoodConsumptionResult.pending("正在进食 " + itemId);
    }

    @Override
    public FoodConsumptionResult pollEating() {
        if (!scheduler.isClientThread()) {
            return FoodConsumptionResult.failed(
                    "自动进食必须在客户端线程执行"
            );
        }
        if (active == null) {
            return FoodConsumptionResult.failed("当前没有进食操作");
        }
        LocalPlayer player = minecraft.player;
        if (player == null || player.isRemoved()) {
            releaseUseKey();
            active = null;
            return FoodConsumptionResult.failed("进食时玩家不可用");
        }
        active.ticks++;
        if (active.ticks >= EATING_TIMEOUT_TICKS) {
            Map<String, Integer> consumed = consumedItems(player, active);
            player.stopUsingItem();
            releaseUseKey();
            restoreFoodSlot(player, active.slot, active.previousSelected);
            active = null;
            return FoodConsumptionResult.failed("进食超时", consumed);
        }
        if (player.isUsingItem()) {
            return FoodConsumptionResult.pending("正在进食 " + active.itemId);
        }
        if (active.ticks < 2) {
            return FoodConsumptionResult.pending(
                    "等待进食 " + active.itemId
            );
        }
        int foodAfter = player.getFoodData().getFoodLevel();
        int countAfter = remainingFoodCount(player, active);
        boolean consumed = countAfter < active.itemCountBefore
                || !active.restricted && foodAfter > active.foodLevelBefore;
        if (consumed) {
            Map<String, Integer> consumedItems = consumedItems(player, active);
            releaseUseKey();
            restoreFoodSlot(player, active.slot, active.previousSelected);
            active = null;
            return FoodConsumptionResult.succeeded(
                    "已完成一次进食",
                    consumedItems
            );
        }
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (canRetryEating(player, gameMode)) {
            if (active.ticks < active.nextRetryTick) {
                return FoodConsumptionResult.pending("等待重试进食 " + active.itemId);
            }
            InteractionResult retry = gameMode.useItem(
                    player,
                    active.slot.hand()
            );
            active.useAttempts++;
            active.nextRetryTick = active.ticks
                    + EATING_RETRY_INTERVAL_TICKS;
            if (retry.consumesAction() || active.useAttempts < EATING_START_RETRIES) {
                return FoodConsumptionResult.pending(
                        "重新开始进食 " + active.itemId
                );
            }
        }
        releaseUseKey();
        restoreFoodSlot(player, active.slot, active.previousSelected);
        active = null;
        return FoodConsumptionResult.failed(
                "食物没有成功消耗（饱食度 "
                        + foodAfter
                        + "，剩余数量 "
                        + countAfter
                        + "）"
        );
    }

    @Override
    public void cancelEating() {
        if (!scheduler.isClientThread() && useGuard.blocksWorldUse()) {
            scheduler.execute(this::cancelEating);
            return;
        }
        if (scheduler.isClientThread()) {
            LocalPlayer player = minecraft.player;
            if (player != null) {
                player.stopUsingItem();
            }
            releaseUseKey();
            if (player != null && active != null) {
                restoreFoodSlot(player, active.slot, active.previousSelected);
            }
        }
        active = null;
    }

    @Override
    public FoodConsumptionResult startAcquiringFood(
            ContainerTarget target,
            String itemId
    ) {
        return startAcquiringFood(target, itemId, false);
    }

    @Override
    public FoodConsumptionResult startAcquiringFood(
            ContainerTarget target, String itemId, boolean replenish
    ) {
        return startAcquiringFood(target, itemId, replenish, replenish ? 64 : 1);
    }

    @Override
    public FoodConsumptionResult startAcquiringFood(
            ContainerTarget target, String itemId, boolean replenish, int count
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(itemId, "itemId");
        String checkedItemId = itemId.trim();
        if (checkedItemId.isEmpty()) {
            return FoodConsumptionResult.failed("食物 ID 不能为空");
        }
        if (count < 1 || count > dev.stowling.v2.model.AutoEatOptions.MAX_REFILL_COUNT) {
            return FoodConsumptionResult.failed("拿取数量必须在 1 到 2304 之间");
        }
        if (!scheduler.isClientThread()) {
            return FoodConsumptionResult.failed(
                    "从食物箱取食必须在客户端线程执行"
            );
        }
        if (active != null) {
            return FoodConsumptionResult.failed("已有进食操作正在进行");
        }
        if (acquiring != null) {
            return FoodConsumptionResult.failed("已有食物取出操作正在进行");
        }
        if (minecraft.gui.screen() != null) {
            return FoodConsumptionResult.failed("请先关闭当前界面");
        }
        acquiring = new ActiveAcquiringFood(target, checkedItemId, count);
        acquiring.deadline = Instant.now().plus(CONTAINER_TIMEOUT);
        return FoodConsumptionResult.pending("正在打开食物箱");
    }

    @Override
    public FoodConsumptionResult pollAcquiringFood() {
        if (!scheduler.isClientThread()) {
            return FoodConsumptionResult.failed(
                    "从食物箱取食必须在客户端线程执行"
            );
        }
        if (acquiring == null) {
            return FoodConsumptionResult.failed("当前没有食物取出操作");
        }
        if (Instant.now().isAfter(acquiring.deadline)) {
            return failAcquiring("从食物箱取出食物超时");
        }
        return switch (acquiring.phase) {
            case OPENING -> pollOpeningFoodContainer();
            case WAITING_FOR_SOURCE -> pollSourcePickup();
            case WAITING_FOR_PLACEMENT -> pollFoodPlacement();
            case RETURNING_REMAINDER -> pollRemainderReturn();
            case CONFIRMING -> pollAcquisitionConfirmation();
            case CLOSING -> pollFoodContainerClosing();
        };
    }

    @Override
    public void cancelAcquiringFood() {
        if (scheduler.isClientThread() && acquiring != null && requireContainerScreen() != null) {
            returnCarriedToSource();
            closeContainer();
        }
        acquiring = null;
    }

    private FoodConsumptionResult pollOpeningFoodContainer() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            if (!acquiring.openSent) {
                openBlock(acquiring.target);
                acquiring.openSent = true;
                acquiring.deadline = Instant.now().plus(CONTAINER_TIMEOUT);
            }
            return FoodConsumptionResult.pending("等待食物箱界面");
        }
        if (!acquiring.openSent) {
            return failAcquiring("请先关闭当前界面");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (playerSlots(menu).isEmpty()
                || containerSlots(menu).isEmpty()) {
            return FoodConsumptionResult.pending("等待食物箱槽位加载");
        }
        if (!menu.getCarried().isEmpty()) {
            return failAcquiring("请先清空手持槽");
        }
        acquiring.menu = menu;
        return prepareFoodTransfer(menu);
    }

    private FoodConsumptionResult prepareFoodTransfer(AbstractContainerMenu menu) {
        int sourceSlot = findFoodSource(menu, acquiring.itemId);
        if (sourceSlot < 0) {
            if (!acquiring.acquiredItems.isEmpty()) return closeAcquisition();
            return failAcquiring("食物箱中没有指定食物: "
                    + acquiring.itemId);
        }
        ItemStack source = menu.getSlot(sourceSlot).getItem();
        int destinationSlot = findFoodDestination(menu, source);
        if (destinationSlot < 0) {
            if (!acquiring.acquiredItems.isEmpty()) return closeAcquisition();
            return failAcquiring("背包没有空间存放食物");
        }
        acquiring.sourceSlot = sourceSlot;
        acquiring.destinationSlot = destinationSlot;
        acquiring.itemKey = MinecraftItemIdentity.key(source);
        Slot destination = menu.getSlot(destinationSlot);
        int capacity = destination.getMaxStackSize(source) - destination.getItem().getCount();
        acquiring.sourceCount = source.getCount();
        acquiring.takeCount = Math.min(acquiring.remainingCount,
                Math.min(source.getCount(), capacity));
        acquiring.bulkPlacement = acquiring.takeCount == Math.min(source.getCount(), capacity);
        acquiring.placedCount = 0;
        acquiring.placementSent = false;
        acquiring.playerBefore = playerItemCount(
                minecraft.player,
                acquiring.itemKey
        );
        acquiring.containerBefore = containerItemCount(
                menu,
                acquiring.itemKey
        );
        click(menu, sourceSlot, 0, ContainerInput.PICKUP);
        acquiring.phase = AcquisitionPhase.WAITING_FOR_SOURCE;
        acquiring.deadline = Instant.now().plus(TAKING_TIMEOUT);
        return FoodConsumptionResult.pending("正在从食物箱取出食物");
    }

    private FoodConsumptionResult pollSourcePickup() {
        AbstractContainerScreen<?> screen = requireContainerScreen();
        if (screen == null) {
            return failAcquiring("食物箱在取出食物前关闭");
        }
        AbstractContainerMenu menu = screen.getMenu();
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            return FoodConsumptionResult.pending("等待食物进入手持槽");
        }
        if (!acquiring.itemKey.equals(MinecraftItemIdentity.key(carried))
                || carried.getCount() != acquiring.sourceCount) {
            return failAcquiring("食物箱取出的物品与目标食物不匹配");
        }
        placeFood(menu);
        acquiring.phase = AcquisitionPhase.WAITING_FOR_PLACEMENT;
        acquiring.placementSent = true;
        return FoodConsumptionResult.pending("正在将食物放入背包");
    }

    private void placeFood(AbstractContainerMenu menu) {
        click(menu, acquiring.destinationSlot, acquiring.bulkPlacement ? 0 : 1,
                ContainerInput.PICKUP);
        acquiring.placedCount = acquiring.bulkPlacement ? acquiring.takeCount
                : acquiring.placedCount + 1;
        acquiring.deadline = Instant.now().plus(TAKING_TIMEOUT);
    }

    private FoodConsumptionResult pollFoodPlacement() {
        AbstractContainerScreen<?> screen = requireContainerScreen();
        if (screen == null) {
            return failAcquiring("食物箱在放入食物前关闭");
        }
        AbstractContainerMenu menu = screen.getMenu();
        ItemStack carried = menu.getCarried();
        int remaining = acquiring.sourceCount - acquiring.placedCount;
        if (carried.isEmpty() && remaining == 0) {
            acquiring.phase = AcquisitionPhase.CONFIRMING;
            acquiring.confirmationPolls = 0;
            return FoodConsumptionResult.pending("等待服务器确认取食");
        }
        if (carried.isEmpty()) {
            return FoodConsumptionResult.pending("等待食物放入背包");
        }
        if (!acquiring.itemKey.equals(MinecraftItemIdentity.key(carried))) {
            return failAcquiring("手持槽物品已变化");
        }
        if (carried.getCount() != remaining) {
            return FoodConsumptionResult.pending("等待食物放入背包");
        }
        if (acquiring.placedCount < acquiring.takeCount) {
            placeFood(menu);
            return FoodConsumptionResult.pending("正在按指定数量放入食物");
        }
        click(menu, acquiring.sourceSlot, 0, ContainerInput.PICKUP);
        acquiring.phase = AcquisitionPhase.RETURNING_REMAINDER;
        return FoodConsumptionResult.pending("正在放回剩余食物");
    }

    private FoodConsumptionResult pollRemainderReturn() {
        AbstractContainerScreen<?> screen = requireContainerScreen();
        if (screen == null) {
            return failAcquiring("食物箱在放回剩余食物前关闭");
        }
        if (!screen.getMenu().getCarried().isEmpty()) {
            return FoodConsumptionResult.pending("等待剩余食物放回箱子");
        }
        acquiring.phase = AcquisitionPhase.CONFIRMING;
        acquiring.confirmationPolls = 0;
        return FoodConsumptionResult.pending("等待服务器确认取食");
    }

    private FoodConsumptionResult pollAcquisitionConfirmation() {
        AbstractContainerScreen<?> screen = requireContainerScreen();
        if (screen == null) {
            return failAcquiring("食物箱在确认取食前关闭");
        }
        AbstractContainerMenu menu = screen.getMenu();
        acquiring.confirmationPolls++;
        int playerAfter = playerItemCount(
                minecraft.player,
                acquiring.itemKey
        );
        int containerAfter = containerItemCount(menu, acquiring.itemKey);
        boolean confirmed = playerAfter == acquiring.playerBefore + acquiring.takeCount
                && containerAfter == acquiring.containerBefore - acquiring.takeCount;
        if (acquiring.confirmationPolls >= CONFIRM_POLLS
                && confirmed
                && menu.getCarried().isEmpty()) {
            acquiring.acquiredItems.merge(acquiring.itemKey, acquiring.takeCount, Integer::sum);
            acquiring.acquiredSlots.add(MinecraftContainerInteractions.inventoryIndex(
                    menu, acquiring.destinationSlot));
            acquiring.remainingCount -= acquiring.takeCount;
            acquiring.placementSent = false;
            return acquiring.remainingCount > 0 ? prepareFoodTransfer(menu) : closeAcquisition();
        }
        return FoodConsumptionResult.pending("等待服务器确认取食");
    }

    private FoodConsumptionResult closeAcquisition() {
        closeContainer();
        acquiring.phase = AcquisitionPhase.CLOSING;
        acquiring.deadline = Instant.now().plus(CLOSING_TIMEOUT);
        return FoodConsumptionResult.pending("正在关闭食物箱");
    }

    private FoodConsumptionResult pollFoodContainerClosing() {
        if (requireContainerScreen() != null) {
            return FoodConsumptionResult.pending("等待食物箱关闭");
        }
        Map<String, Integer> items = Map.copyOf(acquiring.acquiredItems);
        int acquired = items.values().stream().mapToInt(Integer::intValue).sum();
        var acquiredSlots = Set.copyOf(acquiring.acquiredSlots);
        acquiring = null;
        return new FoodConsumptionResult(ContainerTransferState.SUCCEEDED,
                "已从食物箱取出 " + acquired + " 个食物", items, acquiredSlots);
    }

    private FoodConsumptionResult failAcquiring(String message) {
        String itemKey = acquiring.itemKey;
        Map<String, Integer> items = new LinkedHashMap<>(acquiring.acquiredItems);
        if (acquiring.placementSent) {
            int current = playerItemCount(minecraft.player, itemKey);
            int observed = Math.max(
                    0,
                    current - acquiring.playerBefore
            );
            int acquired = Math.min(observed, acquiring.takeCount);
            if (acquired > 0) items.merge(itemKey, acquired, Integer::sum);
        }
        returnCarriedToSource();
        if (requireContainerScreen() != null) {
            closeContainer();
        }
        var acquiredSlots = Set.copyOf(acquiring.acquiredSlots);
        acquiring = null;
        return new FoodConsumptionResult(ContainerTransferState.FAILED,
                message, items, acquiredSlots);
    }

    private void returnCarriedToSource() {
        if (acquiring == null || acquiring.sourceSlot < 0) {
            return;
        }
        AbstractContainerScreen<?> screen = requireContainerScreen();
        if (screen == null) {
            return;
        }
        ItemStack carried = screen.getMenu().getCarried();
        if (carried.isEmpty() || !acquiring.itemKey.equals(MinecraftItemIdentity.key(carried))) {
            return;
        }
        click(
                screen.getMenu(),
                acquiring.sourceSlot,
                0,
                ContainerInput.PICKUP
        );
    }

    private static FoodSlot findFoodSlot(
            LocalPlayer player, String itemId, Set<String> allowedKeys
    ) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if ((allowedKeys == null || MinecraftContainerInteractions.locked(slot))
                    && foodMatches(inventory.getItem(slot), itemId, allowedKeys)) {
                return new FoodSlot(slot, true, InteractionHand.MAIN_HAND, -1);
            }
        }
        if (allowedKeys == null
                && foodMatches(inventory.getItem(Inventory.SLOT_OFFHAND), itemId, allowedKeys)) {
            return new FoodSlot(Inventory.SLOT_OFFHAND, false, InteractionHand.OFF_HAND, -1);
        }
        if (player.containerMenu != player.inventoryMenu) {
            return null;
        }
        for (int index : playerSlots(player.inventoryMenu)) {
            Slot slot = player.inventoryMenu.getSlot(index);
            int inventoryIndex = slot.getContainerSlot();
            if (inventoryIndex >= Inventory.getSelectionSize() && inventoryIndex < 36
                    && (allowedKeys == null || MinecraftContainerInteractions.locked(inventoryIndex))
                    && foodMatches(slot.getItem(), itemId, allowedKeys)) {
                int target = java.util.stream.IntStream.range(0, 9)
                        .filter(candidate -> !MinecraftContainerInteractions.locked(candidate))
                        .findFirst().orElse(-1);
                if (target < 0) return null;
                return new FoodSlot(target, true, InteractionHand.MAIN_HAND, index);
            }
        }
        return null;
    }

    private static int findFoodSource(
            AbstractContainerMenu menu,
            String itemId
    ) {
        for (int slotIndex : containerSlots(menu)) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (foodMatches(stack, itemId, null)) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static int findFoodDestination(
            AbstractContainerMenu menu,
            ItemStack food
    ) {
        int stackingSlot = -1;
        int emptySlot = -1;
        int backpackSlot = -1;
        for (int slotIndex : playerSlots(menu)) {
            Slot slot = menu.getSlot(slotIndex);
            if (!MinecraftContainerInteractions.movable(menu, slotIndex) || !slot.mayPlace(food)) {
                continue;
            }
            int containerSlot = slot.getContainerSlot();
            boolean quickAccess = containerSlot >= 0 && containerSlot < 9
                    || containerSlot == Inventory.SLOT_OFFHAND;
            ItemStack current = slot.getItem();
            boolean canStack = ItemStack.isSameItemSameComponents(current, food)
                    && current.getCount() < slot.getMaxStackSize(food);
            if (!quickAccess) {
                if (backpackSlot < 0 && containerSlot >= 9 && containerSlot < 36
                        && (current.isEmpty() || canStack)) {
                    backpackSlot = slotIndex;
                }
                continue;
            }
            if (current.isEmpty() && emptySlot < 0) {
                emptySlot = slotIndex;
            } else if (canStack && stackingSlot < 0) {
                stackingSlot = slotIndex;
            }
        }
        if (stackingSlot >= 0) return stackingSlot;
        return emptySlot >= 0 ? emptySlot : backpackSlot;
    }

    private static java.util.List<Integer> playerSlots(
            AbstractContainerMenu menu
    ) {
        return menu.slots.stream()
                .filter(slot -> slot.container instanceof Inventory)
                .map(menu.slots::indexOf)
                .toList();
    }

    private static java.util.List<Integer> containerSlots(
            AbstractContainerMenu menu
    ) {
        return menu.slots.stream()
                .filter(slot -> !(slot.container instanceof Inventory))
                .map(menu.slots::indexOf)
                .toList();
    }

    private static int containerItemCount(
            AbstractContainerMenu menu,
            String itemId
    ) {
        return itemCount(menu, containerSlots(menu), itemId);
    }

    private static int itemCount(
            AbstractContainerMenu menu,
            java.util.List<Integer> slots,
            String itemId
    ) {
        int count = 0;
        for (int slotIndex : slots) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (!stack.isEmpty() && itemId.equals(MinecraftItemIdentity.key(stack))) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static boolean foodMatches(ItemStack stack, String itemId, Set<String> allowedKeys) {
        return !stack.isEmpty() && stack.get(DataComponents.FOOD) != null
                && itemId.equals(MinecraftItemIdentity.base(stack))
                && (allowedKeys == null || allowedKeys.contains(MinecraftItemIdentity.key(stack)));
    }

    private static int remainingFoodCount(LocalPlayer player, ActiveEating eating) {
        if (eating.restricted) {
            return playerItemCount(player, eating.itemKey,
                    eating.slot.hand() == InteractionHand.OFF_HAND);
        }
        ItemStack stack = player.getInventory().getItem(eating.slot.index());
        return !stack.isEmpty() && eating.itemKey.equals(MinecraftItemIdentity.key(stack))
                ? stack.getCount() : 0;
    }

    private static Map<String, Integer> consumedItems(
            LocalPlayer player,
            ActiveEating eating
    ) {
        int countAfter = remainingFoodCount(player, eating);
        int consumed = Math.max(0, eating.itemCountBefore - countAfter);
        if (eating.restricted) {
            consumed = Math.min(consumed, 1);
        }
        if (!eating.restricted && consumed == 0
                && player.getFoodData().getFoodLevel()
                        > eating.foodLevelBefore) {
            consumed = 1;
        }
        return consumed > 0
                ? Map.of(eating.itemKey, consumed)
                : Map.of();
    }

    private static int playerItemCount(LocalPlayer player, String itemId) {
        return playerItemCount(player, itemId, false);
    }

    private static int playerItemCount(LocalPlayer player, String itemId, boolean includeOffhand) {
        if (player == null) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        int count = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (!stack.isEmpty() && itemId.equals(MinecraftItemIdentity.key(stack))) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        if (includeOffhand) {
            ItemStack offhand = inventory.getItem(Inventory.SLOT_OFFHAND);
            if (!offhand.isEmpty() && itemId.equals(MinecraftItemIdentity.key(offhand))) {
                count = Math.addExact(count, offhand.getCount());
            }
        }
        return count;
    }

    private boolean canRetryEating(
            LocalPlayer player,
            MultiPlayerGameMode gameMode
    ) {
        if (gameMode == null
                || minecraft.gui.screen() != null
                || active.useAttempts >= EATING_START_RETRIES) {
            return false;
        }
        ItemStack held = player.getItemInHand(active.slot.hand());
        return !held.isEmpty() && active.itemKey.equals(MinecraftItemIdentity.key(held));
    }

    private void releaseUseKey() {
        if (active != null && active.releaseUseKey) {
            minecraft.options.keyUse.setDown(false);
            active.releaseUseKey = false;
        }
        useGuard.end();
    }

    private void swapFoodSlot(LocalPlayer player, FoodSlot slot) {
        if (slot.swapIndex() >= 0 && player.containerMenu == player.inventoryMenu) {
            MinecraftContainerInteractions.clickForUse(minecraft, player.inventoryMenu,
                    slot.swapIndex(), slot.index(), ContainerInput.SWAP);
        }
    }

    private void restoreFoodSlot(LocalPlayer player, FoodSlot slot, int previousSelected) {
        swapFoodSlot(player, slot);
        if (!slot.hotbar()) {
            return;
        }
        player.getInventory().setSelectedSlot(previousSelected);
        if (player.connection != null) {
            player.connection.send(new ServerboundSetCarriedItemPacket(
                    previousSelected
            ));
        }
    }

    private void openBlock(ContainerTarget target) {
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player == null || gameMode == null || !ContainerStandingAccess.canOpen(player, target)) {
            return;
        }
        BlockPos position = new BlockPos(
                target.position().x(),
                target.position().y(),
                target.position().z()
        );
        BlockHitResult hit = new BlockHitResult(
                Vec3.atCenterOf(position),
                Direction.UP,
                position,
                false
        );
        gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
    }

    private void click(
            AbstractContainerMenu menu,
            int slot,
            int button,
            ContainerInput input
    ) {
        MinecraftContainerInteractions.click(minecraft, menu, slot, button, input);
    }

    private void closeContainer() {
        LocalPlayer player = minecraft.player;
        if (player != null) {
            player.closeContainer();
        }
    }

    private AbstractContainerScreen<?> currentContainerScreen() {
        return minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen
                ? screen
                : null;
    }

    private AbstractContainerScreen<?> requireContainerScreen() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        return screen != null && screen.getMenu() == acquiring.menu ? screen : null;
    }

    private record FoodSlot(
            int index,
            boolean hotbar,
            InteractionHand hand,
            int swapIndex
    ) {
    }

    private static final class ActiveEating {
        private final String itemId;
        private final String itemKey;
        private final FoodSlot slot;
        private final int previousSelected;
        private final int foodLevelBefore;
        private final int itemCountBefore;
        private final boolean restricted;
        private int nextRetryTick = EATING_RETRY_INTERVAL_TICKS;
        private int useAttempts = 1;
        private boolean releaseUseKey;
        private int ticks;

        private ActiveEating(
                String itemId,
                String itemKey,
                FoodSlot slot,
                int previousSelected,
                int foodLevelBefore,
                int itemCountBefore,
                boolean restricted,
                boolean releaseUseKey
        ) {
            this.itemId = itemId;
            this.itemKey = itemKey;
            this.slot = slot;
            this.previousSelected = previousSelected;
            this.foodLevelBefore = foodLevelBefore;
            this.itemCountBefore = itemCountBefore;
            this.restricted = restricted;
            this.releaseUseKey = releaseUseKey;
        }
    }

    private enum AcquisitionPhase {
        OPENING,
        WAITING_FOR_SOURCE,
        WAITING_FOR_PLACEMENT,
        RETURNING_REMAINDER,
        CONFIRMING,
        CLOSING
    }

    private static final class ActiveAcquiringFood {
        private final ContainerTarget target;
        private final String itemId;
        private final Map<String, Integer> acquiredItems = new LinkedHashMap<>();
        private final Set<Integer> acquiredSlots = new java.util.LinkedHashSet<>();
        private int remainingCount;
        private AbstractContainerMenu menu;
        private String itemKey;
        private AcquisitionPhase phase = AcquisitionPhase.OPENING;
        private Instant deadline;
        private boolean openSent;
        private int sourceSlot = -1;
        private int destinationSlot = -1;
        private int playerBefore;
        private int containerBefore;
        private int sourceCount;
        private int takeCount;
        private int placedCount;
        private boolean bulkPlacement;
        private boolean placementSent;
        private int confirmationPolls;

        private ActiveAcquiringFood(
                ContainerTarget target,
                String itemId,
                int count
        ) {
            this.target = target;
            this.itemId = itemId;
            remainingCount = count;
        }
    }
}
