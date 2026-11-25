package dev.stowling.client.automation;

import dev.stowling.automation.ContainerClickPlan;
import dev.stowling.automation.ContainerInteractionBatch;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.NavigationPort;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.v2.runtime.TaskTrace;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
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

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final NavigationPort navigation;
    private ActiveDeposit active;
    private ContainerTarget retainedTarget;
    private long nextBatch;

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
        return startDeposit(target, items, true);
    }

    @Override
    public ContainerTransferResult startCollectedCacheDeposit(ContainerTarget target,
            Map<String, Integer> items) {
        return startDeposit(target, items, false);
    }

    private ContainerTransferResult startDeposit(ContainerTarget target, Map<String, Integer> items,
            boolean avoidPickups) {
        return startDeposit(target, items, avoidPickups, false);
    }

    @Override
    public ContainerTransferResult startPickupCacheDeposit(ContainerTarget target,
            Map<String, Integer> items) {
        return startDeposit(target, items, false, true);
    }

    @Override
    public boolean isPickupCacheFull(ContainerTarget target) {
        if (!scheduler.isClientThread() || active != null || !target.equals(retainedTarget)) {
            return false;
        }
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null || !MinecraftContainerInteractions.ready(screen.getMenu())) return false;
        AbstractContainerMenu menu = screen.getMenu();
        List<Integer> slots = containerSlots(menu);
        for (int index : slots) {
            Slot slot = menu.getSlot(index);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || stack.getCount() < slot.getMaxStackSize(stack)) return false;
        }
        return !slots.isEmpty();
    }

    private ContainerTransferResult startDeposit(ContainerTarget target, Map<String, Integer> items,
            boolean avoidPickups, boolean keepOpen) {
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
        active = new ActiveDeposit(target, remaining, avoidPickups, keepOpen);
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (keepOpen && target.equals(retainedTarget) && screen != null
                && MinecraftContainerInteractions.ready(screen.getMenu())) {
            active.phase = Phase.READY;
            active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
            return pollReady();
        }
        active.visit = new MinecraftContainerVisit(minecraft, navigation, target, avoidPickups);
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
        if (active.phase == Phase.RESYNCHRONIZING) {
            return pollResynchronization();
        }
        if (active.visit == null && Instant.now().isAfter(active.deadline)) {
            return timeout();
        }
        return switch (active.phase) {
            case OPENING -> pollOpening();
            case READY -> pollReady();
            case CONFIRMING -> pollConfirmation();
            case ROLLBACK -> pollRollback();
            case RESYNCHRONIZING -> pollResynchronization();
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
        if (active.batch != null) {
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
            if (!MinecraftContainerInteractions.ready(menu) || playerSlots(menu).isEmpty()
                    || containerSlots(menu).isEmpty()) {
                return ContainerTransferResult.pending(
                        "等待容器槽位加载",
                        remaining()
                );
            }
            active.phase = Phase.READY;
            active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
            return pollReady();
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
        if (active.keepOpen) return beginOrdinaryBatch(menu, candidate);
        active.phase = Phase.CONFIRMING;
        active.batch = new DepositBatchConfirmation();
        active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
        active.pendingStartedAt = System.nanoTime();
        var budget = new ContainerInteractionBatch(candidate.itemId(), true);
        var before = inventoryCounts(menu);
        Map<String, Integer> request = new LinkedHashMap<>(active.remaining);
        while (candidate != null) {
            int clicks = candidate.plan().usesQuickMove() ? 1
                    : ContainerClickPlan.exact(menu.getSlot(candidate.slot()).getItem().getCount(),
                            candidate.plan().moveCount()).size();
            String key = candidate.itemId();
            if (!budget.accepts(key, clicks)) break;
            active.sourceSlot = candidate.slot();
            int moved = candidate.plan().moveCount();
            active.batch.record(key, moved, before);
            if (candidate.plan().usesQuickMove()) {
                click(menu, candidate.slot(), CURSOR_PRIMARY, ContainerInput.QUICK_MOVE);
            } else {
                splitDeposit(menu, candidate.slot(), candidate.plan());
            }
            budget.record(moved, clicks);
            if (!menu.getCarried().isEmpty()) return beginRollback("无法安全完成批量存入");
            decrement(request, key, moved);
            candidate = findCandidate(menu, request);
        }
        return ContainerTransferResult.pending(
                "等待服务器确认存入",
                remaining()
        );
    }

    private ContainerTransferResult beginOrdinaryBatch(AbstractContainerMenu menu,
            TransferCandidate candidate) {
        active.phase = Phase.CONFIRMING;
        active.batch = new DepositBatchConfirmation();
        active.batchMenu = menu;
        active.batchId = ++nextBatch;
        active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
        active.pendingStartedAt = System.nanoTime();
        long generation = MinecraftContainerInteractions.generation(menu);
        var before = MinecraftContainerInteractions.serverCounts(menu, containerSlots(menu));
        var budget = new ContainerInteractionBatch(candidate.itemId(), true);
        Set<Integer> sources = new LinkedHashSet<>();
        while (candidate != null && budget.accepts(candidate.itemId(), 1)) {
            active.sourceSlot = candidate.slot();
            int moved = candidate.plan().moveCount();
            active.batch.record(candidate.itemId(), moved, item ->
                    new DepositBatchConfirmation.Counts(0, before.getOrDefault(item, 0), 0));
            MinecraftContainerInteractions.quickMoveConfirmed(minecraft, menu, candidate.slot());
            sources.add(candidate.slot());
            budget.record(moved, 1);
            candidate = findCandidate(menu, active.remaining, sources);
        }
        active.batch.awaitServer(menu, generation, sources.size());
        TaskTrace.event("CALL", "deposit.batch", "batch=" + active.batchId
                + "; menu=" + menu.containerId + "; sourceSlots=" + sources
                + "; transfers=" + sources.size() + "; planned=" + budget.count()
                + "; expectedGeneration=" + (generation + sources.size()), 0, false);
        return ContainerTransferResult.pending("等待本批服务器槽位快照", remaining());
    }

    private ContainerTransferResult confirmOrdinaryBatch(AbstractContainerMenu menu) {
        if (menu != active.batchMenu) {
            return finishFailure("存入菜单已失效，本批未确认，剩余物品保留在背包");
        }
        var counts = MinecraftContainerInteractions.serverCounts(menu, containerSlots(menu));
        try {
            active.batch.observeServer(menu, MinecraftContainerInteractions.generation(menu),
                    key -> new DepositBatchConfirmation.Counts(0, counts.getOrDefault(key, 0), 0));
        } catch (IllegalStateException failure) {
            return finishFailure(failure.getMessage());
        }
        if (!active.batch.settled()) {
            return ContainerTransferResult.pending("原地等待服务器处理本批存入", remaining());
        }
        TaskTrace.event("RETURN", "deposit.batch", "batch=" + active.batchId
                + "; generation=" + MinecraftContainerInteractions.generation(menu)
                + "; accepted=" + active.batch.accepted(), 0, false);
        if (!menu.getCarried().isEmpty()) return beginRollback("服务器确认后鼠标仍有物品");
        if (active.batch.accepted().isEmpty()) {
            return ContainerTransferResult.pending("服务器尚未接受本批存入，原地等待确认", remaining());
        }
        applyConfirmed();
        return succeed("本批实际存入已由服务器确认，继续核对背包");
    }

    private ContainerTransferResult pollConfirmation() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            return awaitResolution("容器已关闭，等待恢复后确认在途物品");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (active.keepOpen) return confirmOrdinaryBatch(menu);
        if (!menu.getCarried().isEmpty()) {
            restoreCarried(menu);
            if (!menu.getCarried().isEmpty()) {
                return beginRollback("服务器未完成拆分归还");
            }
        }
        if (observeBatch(menu, DepositBatchConfirmation.Mode.LIVE)) {
            return beginResynchronization("箱子数量与拾取回执不一致，立即读取服务器快照");
        }
        if (active.batch.settled()) {
            return settleConfirmed("服务器没有确认到物品转出，尝试其他缓存");
        }
        if (!active.avoidPickups && active.batch.stalled(
                Duration.ofNanos(System.nanoTime() - active.pendingStartedAt))) {
            return beginResynchronization("原地存入确认停滞，保持原站位规则重新核对");
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
        TaskTrace.event("TIMEOUT", "deposit.deadline", "phase=" + active.phase
                + "; remaining=" + active.remaining, 0, false);
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            if (active.batch != null || active.phase == Phase.ROLLBACK) {
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
        for (DepositTransferPlan.Placement placement : plan.placements()) {
            MinecraftContainerInteractions.moveExact(minecraft, menu, sourceSlot,
                    placement.slot(), placement.count());
        }
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

    private ContainerTransferResult settleConfirmed(String failureMessage) {
        if (!active.batch.fullyAccepted()) {
            int accepted = active.batch.accepted().values().stream().mapToInt(Integer::intValue)
                    .sum();
            return finishFailure(accepted == 0 ? failureMessage
                    : "服务器仅确认存入 " + accepted + " 个物品，剩余物品需要换箱");
        }
        applyConfirmed();
        active.phase = Phase.READY;
        active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
        return active.remaining.isEmpty() ? complete() : pollReady();
    }

    private ContainerTransferResult reconcileBeforeFailure(
            AbstractContainerMenu menu,
            String message
    ) {
        if (active.batch == null) {
            return finishFailure(message);
        }
        if (active.keepOpen) {
            if (Instant.now().isAfter(active.deadline)) {
                return finishFailure("服务器未在期限内确认存入，停止任务；未确认数量不记为已存入");
            }
            return confirmOrdinaryBatch(menu);
        }
        observeBatch(menu, DepositBatchConfirmation.Mode.SETTLEMENT);
        if (active.batch.settled()) {
            return settleConfirmed(message);
        }
        if (Instant.now().isAfter(active.deadline)) {
            return beginResynchronization(message);
        }
        return ContainerTransferResult.pending("等待服务器确认实际存入数量", remaining());
    }

    private Function<String, DepositBatchConfirmation.Counts> inventoryCounts(
            AbstractContainerMenu menu
    ) {
        Map<String, Integer> player = itemCounts(menu, playerSlots(menu));
        Map<String, Integer> container = itemCounts(menu, containerSlots(menu));
        return key -> new DepositBatchConfirmation.Counts(player.getOrDefault(key, 0),
                container.getOrDefault(key, 0), MinecraftInventoryPickups.count(minecraft, key));
    }

    private boolean observeBatch(AbstractContainerMenu menu, DepositBatchConfirmation.Mode mode) {
        var current = inventoryCounts(menu);
        boolean needsSnapshot = active.batch.observe(mode, current);
        if (TaskTrace.active()) {
            TaskTrace.event("WAIT", "deposit.confirm", "phase=" + active.phase
                    + "; moves=" + active.batch.describe(current),
                    (System.nanoTime() - active.pendingStartedAt) / 1_000_000, true);
        }
        return needsSnapshot;
    }

    private ContainerTransferResult beginResynchronization(String message) {
        TaskTrace.event("RESYNC", "deposit.reopen", "reason=" + message
                + "; target=" + active.target.position(),
                0, false);
        if (minecraft.player == null) return finishFailure("重新核对存入时玩家不可用");
        if (!minecraft.player.containerMenu.getCarried().isEmpty()) {
            return beginRollback(message);
        }
        closeContainer();
        active.phase = Phase.RESYNCHRONIZING;
        active.rollbackMessage = message;
        active.batch.resetUnsettled();
        // Reopening must preserve the caller's standing and pickup policy.
        active.visit = new MinecraftContainerVisit(minecraft, navigation, active.target,
                active.avoidPickups);
        active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        return ContainerTransferResult.pending("正在重新读取原缓存，核对实际存入", remaining());
    }

    private ContainerTransferResult pollResynchronization() {
        if (active.visit == null && Instant.now().isAfter(active.deadline)) {
            return finishFailure("重新读取缓存后仍无法核对存入数量，请检查容器或背包变化");
        }
        if (active.visit != null) {
            TaskModuleResult visit = active.visit.tick();
            if (visit.state() == TaskModuleState.FAILED) return finishFailure(visit.message());
            if (visit.state() != TaskModuleState.SUCCEEDED) {
                return ContainerTransferResult.pending(visit.message(), remaining());
            }
            AbstractContainerScreen<?> screen = currentContainerScreen();
            if (screen == null || !MinecraftContainerInteractions.ready(screen.getMenu())) {
                return ContainerTransferResult.pending("等待缓存完整服务器快照", remaining());
            }
            active.visit = null;
            active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
        }
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null || !screen.getMenu().getCarried().isEmpty()) {
            return ContainerTransferResult.pending("等待重新核对的缓存界面", remaining());
        }
        AbstractContainerMenu menu = screen.getMenu();
        observeBatch(menu, DepositBatchConfirmation.Mode.SERVER_SNAPSHOT);
        if (!active.batch.settled()) {
            return ContainerTransferResult.pending("核对缓存服务器快照中的实际存入量", remaining());
        }
        return settleConfirmed("重新核对后没有确认到物品转出，尝试其他缓存");
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
        if (active.keepOpen) retainedTarget = active.target;
        else closeContainer();
        var transferred = Map.copyOf(active.transferred);
        active = null;
        return new ContainerTransferResult(dev.stowling.automation.ContainerTransferState.SUCCEEDED,
                message, remaining, transferred);
    }

    private ContainerTransferResult fail(String message) {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            if (active.batch != null) {
                return awaitResolution("容器已关闭，等待恢复后确认在途物品");
            }
            return finishFailure(message);
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!restoreCarried(menu)) {
            return beginRollback(message);
        }
        if (active.batch != null) {
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
        if (active.keepOpen) return finishFailure("存入界面已关闭，服务器批次未确认，停止继续拾取");
        if (active.batch != null) return beginResynchronization(message);
        active.phase = Phase.ROLLBACK;
        if (active.rollbackMessage.isEmpty()) {
            active.rollbackMessage = message;
        }
        return ContainerTransferResult.pending(message, remaining());
    }

    private ContainerTransferResult finishFailure(String message) {
        if (active.visit != null) active.visit.cancelNavigation();
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen != null && !restoreCarried(screen.getMenu())) {
            return beginRollback(message);
        }
        applyConfirmed();
        Map<String, Integer> remaining = remaining();
        closeContainer();
        var transferred = Map.copyOf(active.transferred);
        active = null;
        return new ContainerTransferResult(dev.stowling.automation.ContainerTransferState.FAILED,
                message, remaining, transferred);
    }

    private void applyConfirmed() {
        if (active.batch != null) {
            active.batch.accepted().forEach((key, count) -> {
                decrement(active.remaining, key, count);
                active.transferred.merge(key, count, Math::addExact);
            });
            active.batch = null;
            active.sourceSlot = -1;
        }
    }

    private Map<String, Integer> remaining() {
        return active == null ? Map.of() : Map.copyOf(active.remaining);
    }

    private TransferCandidate findCandidate(
            AbstractContainerMenu menu,
            Map<String, Integer> remaining
    ) {
        return findCandidate(menu, remaining, Set.of());
    }

    private TransferCandidate findCandidate(
            AbstractContainerMenu menu,
            Map<String, Integer> remaining,
            Set<Integer> transferredSlots
    ) {
        if (remaining.isEmpty()) return null;
        for (int slotIndex : playerSlots(menu)) {
            if (transferredSlots.contains(slotIndex)) continue;
            Slot slot = menu.getSlot(slotIndex);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !slot.mayPickup(minecraft.player)) continue;
            String key = itemId(stack);
            int wanted = active.keepOpen ? stack.getCount() : remaining.getOrDefault(key, 0);
            if (wanted <= 0) continue;
            DepositTransferPlan plan = DepositTransferPlan.create(
                    wanted,
                    stack.getCount(),
                    destinationSlots(menu, stack)
            );
            if (!plan.hasMovement()) {
                continue;
            }
            if (!active.keepOpen && !plan.usesQuickMove() && plan.placements().size() > 1) {
                var first = plan.placements().getFirst();
                plan = DepositTransferPlan.create(wanted, stack.getCount(),
                        List.of(new DepositTransferPlan.Destination(first.slot(), first.count())));
            }
            return new TransferCandidate(slotIndex, key, plan);
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
        return MinecraftContainerInteractions.transferSlots(menu, true);
    }

    private static List<Integer> containerSlots(AbstractContainerMenu menu) {
        return MinecraftContainerInteractions.slots(menu, false);
    }

    private static Map<String, Integer> itemCounts(
            AbstractContainerMenu menu,
            List<Integer> slots
    ) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int slotIndex : slots) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (!stack.isEmpty()) counts.merge(itemId(stack), stack.getCount(), Math::addExact);
        }
        return counts;
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
        TaskTrace.event("CALL", "game.useItemOn", "openContainer=" + target.position(), 0, false);
        var interaction = gameMode.useItemOn(player, InteractionHand.MAIN_HAND, hit);
        TaskTrace.event("RETURN", "game.useItemOn",
                dev.stowling.v2.runtime.TaskTraceValues.describe(interaction), 0, false);
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
        retainedTarget = null;
        LocalPlayer player = minecraft.player;
        if (player != null) {
            TaskTrace.event("CALL", "game.closeContainer", "", 0, false);
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
        ROLLBACK,
        RESYNCHRONIZING
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
        private final Map<String, Integer> transferred = new LinkedHashMap<>();
        private boolean avoidPickups;
        private final boolean keepOpen;
        private Phase phase = Phase.OPENING;
        private Instant deadline;
        private boolean openSent;
        private DepositBatchConfirmation batch;
        private AbstractContainerMenu batchMenu;
        private long batchId;
        private int sourceSlot = -1;
        private long pendingStartedAt;
        private String rollbackMessage = "";

        private ActiveDeposit(
                ContainerTarget target,
                Map<String, Integer> remaining,
                boolean avoidPickups,
                boolean keepOpen
        ) {
            this.target = target;
            this.remaining = remaining;
            this.avoidPickups = avoidPickups;
            this.keepOpen = keepOpen;
        }
    }
}
