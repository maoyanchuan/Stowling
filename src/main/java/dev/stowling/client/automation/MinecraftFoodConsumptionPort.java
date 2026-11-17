package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.FoodStatus;
import dev.stowling.client.interaction.ClientActionScheduler;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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
    private ActiveEating active;
    private ActiveAcquiringFood acquiring;

    public MinecraftFoodConsumptionPort(
            Minecraft minecraft,
            ClientActionScheduler scheduler
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
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
        FoodSlot slot = findFoodSlot(player.getInventory(), itemId);
        if (slot == null) {
            return FoodConsumptionResult.failed(
                    "快捷栏或副手没有食物: " + itemId
            );
        }
        int previousSelected = player.getInventory().getSelectedSlot();
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
            restoreSelected(player, previousSelected, slot.hotbar());
            return FoodConsumptionResult.failed(
                    "无法使用指定食物: " + itemId
            );
        }
        active = new ActiveEating(
                itemId,
                slot.index(),
                slot.hotbar(),
                slot.hand(),
                previousSelected,
                player.getFoodData().getFoodLevel(),
                itemCount(player, slot),
                EATING_RETRY_INTERVAL_TICKS,
                !useKeyWasDown
        );
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
            restoreSelected(
                    player,
                    active.previousSelected,
                    active.hotbar
            );
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
        int countAfter = itemCount(player, active);
        boolean consumed = foodAfter > active.foodLevelBefore
                || countAfter < active.itemCountBefore;
        if (consumed) {
            Map<String, Integer> consumedItems = consumedItems(player, active);
            releaseUseKey();
            restoreSelected(
                    player,
                    active.previousSelected,
                    active.hotbar
            );
            active = null;
            return FoodConsumptionResult.succeeded(
                    "已完成一次进食",
                    consumedItems
            );
        }
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (canRetryEating(player, gameMode)) {
            InteractionResult retry = gameMode.useItem(
                    player,
                    active.hand
            );
            active.useAttempts++;
            active.nextRetryTick = active.ticks
                    + EATING_RETRY_INTERVAL_TICKS;
            if (retry.consumesAction()) {
                return FoodConsumptionResult.pending(
                        "重新开始进食 " + active.itemId
                );
            }
        }
        releaseUseKey();
        restoreSelected(
                player,
                active.previousSelected,
                active.hotbar
        );
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
        if (scheduler.isClientThread()) {
            LocalPlayer player = minecraft.player;
            if (player != null) {
                player.stopUsingItem();
            }
            releaseUseKey();
            if (player != null && active != null) {
                restoreSelected(
                        player,
                        active.previousSelected,
                        active.hotbar
                );
            }
        }
        active = null;
    }

    @Override
    public FoodConsumptionResult startAcquiringFood(
            ContainerTarget target,
            String itemId
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(itemId, "itemId");
        String checkedItemId = itemId.trim();
        if (checkedItemId.isEmpty()) {
            return FoodConsumptionResult.failed("食物 ID 不能为空");
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
        acquiring = new ActiveAcquiringFood(target, checkedItemId);
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
            case WAITING_FOR_ONE -> pollSingleItemPlacement();
            case RETURNING_REMAINDER -> pollRemainderReturn();
            case CONFIRMING -> pollAcquisitionConfirmation();
            case CLOSING -> pollFoodContainerClosing();
        };
    }

    @Override
    public void cancelAcquiringFood() {
        if (scheduler.isClientThread()) {
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
            closeContainer();
            return FoodConsumptionResult.pending("正在关闭当前界面");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (playerSlots(menu).isEmpty()
                || containerSlots(menu).isEmpty()) {
            return FoodConsumptionResult.pending("等待食物箱槽位加载");
        }
        int sourceSlot = findFoodSource(menu, acquiring.itemId);
        if (sourceSlot < 0) {
            return failAcquiring("食物箱中没有指定食物: "
                    + acquiring.itemId);
        }
        ItemStack source = menu.getSlot(sourceSlot).getItem();
        int destinationSlot = findFoodDestination(menu, source);
        if (destinationSlot < 0) {
            return failAcquiring("快捷栏或副手没有空间存放食物");
        }
        acquiring.sourceSlot = sourceSlot;
        acquiring.destinationSlot = destinationSlot;
        acquiring.playerBefore = playerItemCount(
                minecraft.player,
                acquiring.itemId
        );
        acquiring.containerBefore = containerItemCount(
                menu,
                acquiring.itemId
        );
        click(menu, sourceSlot, 0, ContainerInput.PICKUP);
        acquiring.phase = AcquisitionPhase.WAITING_FOR_SOURCE;
        acquiring.deadline = Instant.now().plus(TAKING_TIMEOUT);
        return FoodConsumptionResult.pending("正在从食物箱取出一个食物");
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
        if (!itemMatches(carried, acquiring.itemId)) {
            return failAcquiring("食物箱取出的物品与目标食物不匹配");
        }
        acquiring.pickedCount = carried.getCount();
        click(
                menu,
                acquiring.destinationSlot,
                1,
                ContainerInput.PICKUP
        );
        acquiring.phase = AcquisitionPhase.WAITING_FOR_ONE;
        return FoodConsumptionResult.pending("正在放入一个食物");
    }

    private FoodConsumptionResult pollSingleItemPlacement() {
        AbstractContainerScreen<?> screen = requireContainerScreen();
        if (screen == null) {
            return failAcquiring("食物箱在放入食物前关闭");
        }
        AbstractContainerMenu menu = screen.getMenu();
        ItemStack carried = menu.getCarried();
        if (carried.isEmpty()) {
            acquiring.phase = AcquisitionPhase.CONFIRMING;
            acquiring.confirmationPolls = 0;
            return FoodConsumptionResult.pending("等待服务器确认取食");
        }
        if (carried.getCount() >= acquiring.pickedCount) {
            return FoodConsumptionResult.pending("等待食物放入快捷栏");
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
                acquiring.itemId
        );
        int containerAfter = containerItemCount(menu, acquiring.itemId);
        boolean confirmed = playerAfter == acquiring.playerBefore + 1
                && containerAfter == acquiring.containerBefore - 1;
        if (acquiring.confirmationPolls >= CONFIRM_POLLS
                && confirmed
                && menu.getCarried().isEmpty()) {
            acquiring.acquiredCount = 1;
            closeContainer();
            acquiring.phase = AcquisitionPhase.CLOSING;
            acquiring.deadline = Instant.now().plus(CLOSING_TIMEOUT);
            return FoodConsumptionResult.pending("正在关闭食物箱");
        }
        return FoodConsumptionResult.pending("等待服务器确认取食");
    }

    private FoodConsumptionResult pollFoodContainerClosing() {
        if (currentContainerScreen() != null) {
            return FoodConsumptionResult.pending("等待食物箱关闭");
        }
        String itemId = acquiring.itemId;
        int acquired = acquiring.acquiredCount;
        acquiring = null;
        return FoodConsumptionResult.succeeded(
                "已从食物箱取出一个食物",
                acquired > 0 ? Map.of(itemId, acquired) : Map.of()
        );
    }

    private FoodConsumptionResult failAcquiring(String message) {
        String itemId = acquiring.itemId;
        int acquired = acquiring.acquiredCount;
        if (acquiring.sourceSlot >= 0) {
            int current = playerItemCount(minecraft.player, itemId);
            int observed = Math.max(
                    0,
                    current - acquiring.playerBefore
            );
            acquired = Math.max(acquired, observed);
        }
        returnCarriedToSource();
        closeContainer();
        acquiring = null;
        return FoodConsumptionResult.failed(
                message,
                acquired > 0 ? Map.of(itemId, acquired) : Map.of()
        );
    }

    private void returnCarriedToSource() {
        if (acquiring == null || acquiring.sourceSlot < 0) {
            return;
        }
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null || screen.getMenu().getCarried().isEmpty()) {
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
            Inventory inventory,
            String itemId
    ) {
        for (int slot = 0; slot < Inventory.getSelectionSize(); slot++) {
            if (itemMatches(inventory.getItem(slot), itemId)) {
                return new FoodSlot(
                        slot,
                        true,
                        InteractionHand.MAIN_HAND
                );
            }
        }
        if (itemMatches(
                inventory.getItem(Inventory.SLOT_OFFHAND),
                itemId
        )) {
            return new FoodSlot(
                    Inventory.SLOT_OFFHAND,
                    false,
                    InteractionHand.OFF_HAND
            );
        }
        return null;
    }

    private static int findFoodSource(
            AbstractContainerMenu menu,
            String itemId
    ) {
        for (int slotIndex : containerSlots(menu)) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
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
        for (int slotIndex : playerSlots(menu)) {
            Slot slot = menu.getSlot(slotIndex);
            if (!slot.mayPlace(food)) {
                continue;
            }
            int containerSlot = slot.getContainerSlot();
            boolean quickAccess = containerSlot >= 0 && containerSlot < 9
                    || containerSlot == Inventory.SLOT_OFFHAND;
            if (!quickAccess) {
                continue;
            }
            ItemStack current = slot.getItem();
            if (current.isEmpty() && emptySlot < 0) {
                emptySlot = slotIndex;
            } else if (ItemStack.isSameItemSameComponents(current, food)
                    && current.getCount() < slot.getMaxStackSize(food)
                    && stackingSlot < 0) {
                stackingSlot = slotIndex;
            }
        }
        return stackingSlot >= 0 ? stackingSlot : emptySlot;
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

    private static int playerItemCount(
            AbstractContainerMenu menu,
            String itemId
    ) {
        return itemCount(menu, playerSlots(menu), itemId);
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
            if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }

    private static String itemId(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static boolean itemMatches(ItemStack stack, String itemId) {
        return !stack.isEmpty()
                && itemId.equals(
                        BuiltInRegistries.ITEM.getKey(stack.getItem())
                                .toString()
                );
    }

    private static int itemCount(LocalPlayer player, FoodSlot slot) {
        return player.getInventory().getItem(slot.index()).getCount();
    }

    private static int itemCount(LocalPlayer player, ActiveEating eating) {
        return player.getInventory().getItem(eating.slotIndex).getCount();
    }

    private static Map<String, Integer> consumedItems(
            LocalPlayer player,
            ActiveEating eating
    ) {
        int countAfter = itemCount(player, eating);
        int consumed = Math.max(0, eating.itemCountBefore - countAfter);
        if (consumed == 0
                && player.getFoodData().getFoodLevel()
                        > eating.foodLevelBefore) {
            consumed = 1;
        }
        return consumed > 0
                ? Map.of(eating.itemId, consumed)
                : Map.of();
    }

    private static int playerItemCount(LocalPlayer player, String itemId) {
        if (player == null) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        int count = 0;
        for (ItemStack stack : inventory.getNonEquipmentItems()) {
            if (!stack.isEmpty() && itemId.equals(itemId(stack))) {
                count = Math.addExact(count, stack.getCount());
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
                || active.useAttempts >= EATING_START_RETRIES
                || active.ticks < active.nextRetryTick) {
            return false;
        }
        return itemMatches(
                player.getInventory().getItem(active.slotIndex),
                active.itemId
        );
    }

    private void releaseUseKey() {
        if (active != null && active.releaseUseKey) {
            minecraft.options.keyUse.setDown(false);
            active.releaseUseKey = false;
        }
    }

    private static void restoreSelected(
            LocalPlayer player,
            int previousSelected,
            boolean hotbar
    ) {
        if (!hotbar) {
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
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player != null && gameMode != null) {
            gameMode.handleContainerInput(
                    menu.containerId,
                    slot,
                    button,
                    input,
                    player
            );
        }
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
        return currentContainerScreen();
    }

    private record FoodSlot(
            int index,
            boolean hotbar,
            InteractionHand hand
    ) {
    }

    private static final class ActiveEating {
        private final String itemId;
        private final int slotIndex;
        private final boolean hotbar;
        private final InteractionHand hand;
        private final int previousSelected;
        private final int foodLevelBefore;
        private final int itemCountBefore;
        private int nextRetryTick;
        private int useAttempts = 1;
        private boolean releaseUseKey;
        private int ticks;

        private ActiveEating(
                String itemId,
                int slotIndex,
                boolean hotbar,
                InteractionHand hand,
                int previousSelected,
                int foodLevelBefore,
                int itemCountBefore,
                int nextRetryTick,
                boolean releaseUseKey
        ) {
            this.itemId = itemId;
            this.slotIndex = slotIndex;
            this.hotbar = hotbar;
            this.hand = hand;
            this.previousSelected = previousSelected;
            this.foodLevelBefore = foodLevelBefore;
            this.itemCountBefore = itemCountBefore;
            this.nextRetryTick = nextRetryTick;
            this.releaseUseKey = releaseUseKey;
        }
    }

    private enum AcquisitionPhase {
        OPENING,
        WAITING_FOR_SOURCE,
        WAITING_FOR_ONE,
        RETURNING_REMAINDER,
        CONFIRMING,
        CLOSING
    }

    private static final class ActiveAcquiringFood {
        private final ContainerTarget target;
        private final String itemId;
        private AcquisitionPhase phase = AcquisitionPhase.OPENING;
        private Instant deadline;
        private boolean openSent;
        private int sourceSlot = -1;
        private int destinationSlot = -1;
        private int playerBefore;
        private int containerBefore;
        private int pickedCount;
        private int acquiredCount;
        private int confirmationPolls;

        private ActiveAcquiringFood(
                ContainerTarget target,
                String itemId
        ) {
            this.target = target;
            this.itemId = itemId;
        }
    }
}
