package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.automation.NavigationPort;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftContainerTransferPort
        implements ContainerTransferPort {
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TRANSFER_TIMEOUT = Duration.ofSeconds(8);
    private static final int CURSOR_PRIMARY = 0;
    private static final int CURSOR_SECONDARY = 1;

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final NavigationPort navigation;
    private ActiveDeposit active;

    public MinecraftContainerTransferPort(
            Minecraft minecraft,
            ClientActionScheduler scheduler,
            NavigationPort navigation
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
    }

    @Override
    public ContainerTransferResult startDeposit(
            ContainerTarget target,
            Map<String, Integer> items
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(items, "items");
        Map<String, Integer> remaining = checkedItems(items);
        if (!scheduler.isClientThread()) {
            return ContainerTransferResult.failed(
                    "容器存入必须在客户端线程执行",
                    remaining
            );
        }
        if (remaining.isEmpty()) {
            return ContainerTransferResult.succeeded("没有需要存入的物品", Map.of());
        }
        if (active != null) {
            return ContainerTransferResult.failed(
                    "已有容器存入操作正在进行",
                    remaining
            );
        }
        active = new ActiveDeposit(target, remaining);
        if (target.partner() != null) {
            active.visit = new MinecraftContainerVisit(minecraft, navigation, target);
        }
        active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        return ContainerTransferResult.pending("正在打开容器", remaining);
    }

    @Override
    public ContainerTransferResult pollDeposit() {
        if (!scheduler.isClientThread()) {
            return ContainerTransferResult.failed(
                    "容器存入必须在客户端线程执行",
                    remaining()
            );
        }
        if (active == null) {
            return ContainerTransferResult.failed(
                    "当前没有容器存入操作",
                    Map.of()
            );
        }
        if (active.visit == null && Instant.now().isAfter(active.deadline)) {
            return timeout();
        }
        return switch (active.phase) {
            case OPENING -> pollOpening();
            case READY -> pollReady();
            case CONFIRMING -> pollConfirmation();
            case ROLLBACK -> pollRollback();
        };
    }

    @Override
    public void cancelDeposit() {
        if (!scheduler.isClientThread()) {
            scheduler.execute(this::cancelDeposit);
            return;
        }
        if (active == null) {
            closeContainer();
            return;
        }
        if (active.visit != null) {
            active.visit.cancelNavigation();
        }
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen != null) {
            AbstractContainerMenu menu = screen.getMenu();
            restoreCarried(menu);
            if (!menu.getCarried().isEmpty()) {
                beginRollback("容器存入已取消");
                return;
            }
        }
        if (!active.pendingItemId.isEmpty()) {
            active.phase = Phase.ROLLBACK;
            active.rollbackMessage = "容器存入已取消";
            return;
        }
        closeContainer();
        active = null;
    }

    private ContainerTransferResult pollOpening() {
        if (active.visit != null) {
            TaskModuleResult visit = active.visit.tick();
            if (visit.state() == TaskModuleState.FAILED) {
                return finishFailure(visit.message());
            }
            if (visit.state() != TaskModuleState.SUCCEEDED) {
                return ContainerTransferResult.pending(visit.message(), remaining());
            }
            active.visit = null;
            active.openSent = true;
            active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        }
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen != null) {
            if (!active.openSent) {
                closeContainer();
                return ContainerTransferResult.pending(
                        "正在关闭当前界面",
                        remaining()
                );
            }
            AbstractContainerMenu menu = screen.getMenu();
            if (playerSlots(menu).isEmpty()
                    || containerSlots(menu).isEmpty()) {
                return ContainerTransferResult.pending(
                        "等待容器槽位加载",
                        remaining()
                );
            }
            active.phase = Phase.READY;
            active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
            return ContainerTransferResult.pending(
                    "容器已打开",
                    remaining()
            );
        }
        if (!active.openSent) {
            openBlock(active.target);
            active.openSent = true;
            active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        }
        return ContainerTransferResult.pending("等待容器界面", remaining());
    }

    private ContainerTransferResult pollReady() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            return fail("容器在存入前关闭");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!menu.getCarried().isEmpty()) {
            return ContainerTransferResult.pending(
                    "等待容器光标清空",
                    remaining()
            );
        }
        TransferCandidate candidate = findCandidate(menu, active.remaining);
        if (candidate == null) {
            if (hasRequestedStack(menu, active.remaining)) {
                return succeed("当前容器容量不足，仍有物品待存入");
            }
            return fail("当前容器没有可安全转移的物品");
        }
        active.phase = Phase.CONFIRMING;
        active.pendingItemId = candidate.itemId();
        active.pendingCount = candidate.plan().moveCount();
        active.sourceSlot = candidate.slot();
        active.playerBefore = playerItemCount(menu, candidate.itemId());
        active.containerBefore = containerItemCount(menu, candidate.itemId());
        active.observedDelta = -1;
        active.observationPolls = 0;
        active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
        if (candidate.plan().usesQuickMove()) {
            click(menu, candidate.slot(), CURSOR_PRIMARY, ContainerInput.QUICK_MOVE);
        } else {
            splitDeposit(menu, candidate.slot(), candidate.plan());
        }
        if (!menu.getCarried().isEmpty()) {
            return beginRollback("无法安全完成拆分存入");
        }
        return ContainerTransferResult.pending(
                "等待服务器确认存入",
                remaining()
        );
    }

    private ContainerTransferResult pollConfirmation() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            return awaitResolution("容器已关闭，等待恢复后确认在途物品");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!menu.getCarried().isEmpty()) {
            restoreCarried(menu);
            if (!menu.getCarried().isEmpty()) {
                return beginRollback("服务器未完成拆分归还");
            }
        }
        int playerDelta = active.playerBefore
                - playerItemCount(menu, active.pendingItemId);
        int containerDelta = containerItemCount(menu, active.pendingItemId)
                - active.containerBefore;
        DepositTransferPlan.TransferObservation observation =
                DepositTransferPlan.observeConfirmation(
                        active.pendingCount,
                        active.observedDelta,
                        active.observationPolls,
                        playerDelta,
                        containerDelta
                );
        active.observedDelta = observation.delta();
        active.observationPolls = observation.observations();
        if (observation.stable()) {
            return settleConfirmed(menu, observation.delta());
        }
        return ContainerTransferResult.pending(
                "等待服务器确认实际存入数量",
                remaining()
        );
    }

    private ContainerTransferResult pollRollback() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            return awaitResolution("容器已关闭，等待恢复后确认在途物品");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!restoreCarried(menu)) {
            return rollbackPending();
        }
        return reconcileBeforeFailure(menu, active.rollbackMessage);
    }

    private ContainerTransferResult timeout() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            if (!active.pendingItemId.isEmpty()
                    || active.phase == Phase.ROLLBACK) {
                return awaitResolution("容器已关闭，等待恢复后确认在途物品");
            }
            return finishFailure("容器操作超时");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!menu.getCarried().isEmpty()) {
            if (active.phase != Phase.ROLLBACK) {
                active.phase = Phase.ROLLBACK;
                active.rollbackMessage = "容器操作超时";
            }
            return pollRollback();
        }
        String message = active.phase == Phase.ROLLBACK
                ? active.rollbackMessage
                : "容器操作超时";
        return reconcileBeforeFailure(menu, message);
    }

    private void splitDeposit(
            AbstractContainerMenu menu,
            int sourceSlot,
            DepositTransferPlan plan
    ) {
        click(menu, sourceSlot, CURSOR_PRIMARY, ContainerInput.PICKUP);
        for (DepositTransferPlan.Placement placement : plan.placements()) {
            for (int count = 0; count < placement.count(); count++) {
                click(
                        menu,
                        placement.slot(),
                        CURSOR_SECONDARY,
                        ContainerInput.PICKUP
                );
            }
        }
        click(menu, sourceSlot, CURSOR_PRIMARY, ContainerInput.PICKUP);
    }

    private boolean restoreCarried(AbstractContainerMenu menu) {
        int attempts = menu.slots.size() + 1;
        while (!menu.getCarried().isEmpty() && attempts-- > 0) {
            int slot = findReturnSlot(menu, menu.getCarried(), active.sourceSlot);
            if (slot < 0) {
                return false;
            }
            click(menu, slot, CURSOR_PRIMARY, ContainerInput.PICKUP);
        }
        return menu.getCarried().isEmpty();
    }

    private static int findReturnSlot(
            AbstractContainerMenu menu,
            ItemStack carried,
            int preferredSlot
    ) {
        if (canReturnTo(menu, preferredSlot, carried)) {
            return preferredSlot;
        }
        for (int slotIndex : playerSlots(menu)) {
            if (canReturnTo(menu, slotIndex, carried)) {
                return slotIndex;
            }
        }
        return -1;
    }

    private static boolean canReturnTo(
            AbstractContainerMenu menu,
            int slotIndex,
            ItemStack carried
    ) {
        if (slotIndex < 0 || slotIndex >= menu.slots.size()) {
            return false;
        }
        Slot slot = menu.getSlot(slotIndex);
        if (!slot.mayPlace(carried)) {
            return false;
        }
        ItemStack current = slot.getItem();
        if (current.isEmpty()) {
            return true;
        }
        return ItemStack.isSameItemSameComponents(current, carried)
                && current.getCount() < slot.getMaxStackSize(carried);
    }

    private ContainerTransferResult complete() {
        return succeed("拾取物品已存入容器");
    }

    private ContainerTransferResult settleConfirmed(
            AbstractContainerMenu menu,
            int delta
    ) {
        if (!menu.getCarried().isEmpty()) {
            return beginRollback("确认存入前仍有持有物");
        }
        String itemId = active.pendingItemId;
        boolean complete = delta == active.pendingCount;
        decrement(active.remaining, itemId, delta);
        clearPending();
        if (complete) {
            active.phase = Phase.READY;
            active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
            if (active.remaining.isEmpty()) {
                return complete();
            }
            return ContainerTransferResult.pending(
                    "已存入 " + delta + " 个物品",
                    remaining()
            );
        }
        return finishFailure(
                "服务器仅确认存入 " + delta + " 个物品，剩余物品需要换箱"
        );
    }

    private ContainerTransferResult reconcileBeforeFailure(
            AbstractContainerMenu menu,
            String message
    ) {
        if (active.pendingItemId.isEmpty()) {
            return finishFailure(message);
        }
        int playerDelta = active.playerBefore
                - playerItemCount(menu, active.pendingItemId);
        int containerDelta = containerItemCount(menu, active.pendingItemId)
                - active.containerBefore;
        DepositTransferPlan.TransferObservation observation =
                DepositTransferPlan.observeSettlement(
                        active.pendingCount,
                        active.observedDelta,
                        active.observationPolls,
                        playerDelta,
                        containerDelta
                );
        active.observedDelta = observation.delta();
        active.observationPolls = observation.observations();
        if (!observation.stable()) {
            return ContainerTransferResult.pending(
                    "等待服务器确认实际存入数量",
                    remaining()
            );
        }
        if (observation.delta() > 0) {
            return settleConfirmed(menu, observation.delta());
        }
        return finishFailure(message);
    }

    private ContainerTransferResult succeed(String message) {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen != null) {
            AbstractContainerMenu menu = screen.getMenu();
            if (!restoreCarried(menu)) {
                return beginRollback("存入完成时仍有持有物");
            }
        }
        Map<String, Integer> remaining = remaining();
        closeContainer();
        active = null;
        return ContainerTransferResult.succeeded(message, remaining);
    }

    private ContainerTransferResult fail(String message) {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            if (!active.pendingItemId.isEmpty()) {
                return awaitResolution("容器已关闭，等待恢复后确认在途物品");
            }
            return finishFailure(message);
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!restoreCarried(menu)) {
            return beginRollback(message);
        }
        if (!active.pendingItemId.isEmpty()) {
            return reconcileBeforeFailure(menu, message);
        }
        return finishFailure(message);
    }

    private ContainerTransferResult beginRollback(String message) {
        active.phase = Phase.ROLLBACK;
        active.rollbackMessage = message;
        return rollbackPending();
    }

    private ContainerTransferResult rollbackPending() {
        return ContainerTransferResult.pending(
                "背包没有空间，请腾出空间后继续归还持有物",
                remaining()
        );
    }

    private ContainerTransferResult awaitResolution(String message) {
        active.phase = Phase.ROLLBACK;
        if (active.rollbackMessage.isEmpty()) {
            active.rollbackMessage = message;
        }
        return ContainerTransferResult.pending(message, remaining());
    }

    private ContainerTransferResult finishFailure(String message) {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen != null && !restoreCarried(screen.getMenu())) {
            return beginRollback(message);
        }
        Map<String, Integer> remaining = remaining();
        closeContainer();
        active = null;
        return ContainerTransferResult.failed(message, remaining);
    }

    private void clearPending() {
        active.pendingItemId = "";
        active.pendingCount = 0;
        active.sourceSlot = -1;
        active.observedDelta = -1;
        active.observationPolls = 0;
    }

    private Map<String, Integer> remaining() {
        return active == null ? Map.of() : Map.copyOf(active.remaining);
    }

    private TransferCandidate findCandidate(
            AbstractContainerMenu menu,
            Map<String, Integer> remaining
    ) {
        for (int slotIndex : playerSlots(menu)) {
            Slot slot = menu.getSlot(slotIndex);
            ItemStack stack = slot.getItem();
            if (!matchesRequest(stack, remaining)) {
                continue;
            }
            int wanted = remaining.get(itemId(stack));
            DepositTransferPlan plan = DepositTransferPlan.create(
                    wanted,
                    stack.getCount(),
                    destinationSlots(menu, stack)
            );
            if (!plan.hasMovement()) {
                continue;
            }
            return new TransferCandidate(slotIndex, itemId(stack), plan);
        }
        return null;
    }

    private static boolean hasRequestedStack(
            AbstractContainerMenu menu,
            Map<String, Integer> remaining
    ) {
        for (int slotIndex : playerSlots(menu)) {
            if (matchesRequest(menu.getSlot(slotIndex).getItem(), remaining)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesRequest(
            ItemStack stack,
            Map<String, Integer> remaining
    ) {
        if (stack.isEmpty()) {
            return false;
        }
        return remaining.getOrDefault(itemId(stack), 0) > 0;
    }

    private static List<DepositTransferPlan.Destination> destinationSlots(
            AbstractContainerMenu menu,
            ItemStack stack
    ) {
        List<DepositTransferPlan.Destination> destinations = new ArrayList<>();
        for (int slotIndex : containerSlots(menu)) {
            Slot slot = menu.getSlot(slotIndex);
            if (!slot.mayPlace(stack)) {
                continue;
            }
            ItemStack current = slot.getItem();
            int maximum = slot.getMaxStackSize(stack);
            if (current.isEmpty()) {
                destinations.add(new DepositTransferPlan.Destination(slotIndex, maximum));
            } else if (ItemStack.isSameItemSameComponents(current, stack)) {
                int capacity = Math.max(0, maximum - current.getCount());
                destinations.add(new DepositTransferPlan.Destination(slotIndex, capacity));
            }
        }
        return destinations;
    }

    private static List<Integer> playerSlots(AbstractContainerMenu menu) {
        return menu.slots.stream()
                .filter(slot -> slot.container instanceof Inventory)
                .map(slot -> menu.slots.indexOf(slot))
                .toList();
    }

    private static List<Integer> containerSlots(AbstractContainerMenu menu) {
        return menu.slots.stream()
                .filter(slot -> !(slot.container instanceof Inventory))
                .map(slot -> menu.slots.indexOf(slot))
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
            List<Integer> slots,
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

    private static String itemId(ItemStack stack) {
        return MinecraftItemIdentity.key(stack);
    }

    private static Map<String, Integer> checkedItems(
            Map<String, Integer> items
    ) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : items.entrySet()) {
            String itemId = entry.getKey() == null ? "" : entry.getKey().trim();
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (itemId.isEmpty() || count <= 0) {
                throw new IllegalArgumentException(
                        "container transfer items must be positive"
                );
            }
            result.merge(itemId, count, Math::addExact);
        }
        return result;
    }

    private static void decrement(
            Map<String, Integer> remaining,
            String itemId,
            int amount
    ) {
        int next = remaining.getOrDefault(itemId, 0) - amount;
        if (next <= 0) {
            remaining.remove(itemId);
        } else {
            remaining.put(itemId, next);
        }
    }

    private enum Phase {
        OPENING,
        READY,
        CONFIRMING,
        ROLLBACK
    }

    private record TransferCandidate(
            int slot,
            String itemId,
            DepositTransferPlan plan
    ) {
    }

    private static final class ActiveDeposit {
        private MinecraftContainerVisit visit;
        private final ContainerTarget target;
        private final Map<String, Integer> remaining;
        private Phase phase = Phase.OPENING;
        private Instant deadline;
        private boolean openSent;
        private String pendingItemId = "";
        private int pendingCount;
        private int sourceSlot = -1;
        private int playerBefore;
        private int containerBefore;
        private int observedDelta = -1;
        private int observationPolls;
        private String rollbackMessage = "";

        private ActiveDeposit(
                ContainerTarget target,
                Map<String, Integer> remaining
        ) {
            this.target = target;
            this.remaining = remaining;
        }
    }
}
