package dev.stowling.client.automation;

import dev.stowling.automation.ClassificationBatchPolicy;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerInteractionBatch;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.automation.NavigationPort;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class MinecraftContainerWithdrawalPort
        implements ContainerWithdrawalPort {
    private static final Duration OPEN_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration WITHDRAW_TIMEOUT = Duration.ofSeconds(8);
    private static final int CONFIRM_POLLS = 2;

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final NavigationPort navigation;
    private ActiveWithdrawal active;

    public MinecraftContainerWithdrawalPort(
            Minecraft minecraft,
            ClientActionScheduler scheduler,
            NavigationPort navigation
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.navigation = Objects.requireNonNull(navigation, "navigation");
    }

    @Override
    public ContainerWithdrawalResult startWithdrawal(
            ContainerTarget target,
            Set<String> matchingItemIds
    ) {
        return startWithdrawal(target, matchingItemIds, false);
    }

    @Override
    public ContainerWithdrawalResult startClassificationBatch(
            ContainerTarget target, Set<String> matchingItemIds) {
        return startWithdrawal(target, matchingItemIds, true);
    }

    @Override
    public ContainerWithdrawalResult startClassificationBatch(ContainerTarget target,
            Set<String> matchingItemIds, Set<String> fullBoxCategories) {
        return startClassificationBatch(target, matchingItemIds, fullBoxCategories, List.of());
    }

    @Override
    public ContainerWithdrawalResult startClassificationBatch(ContainerTarget target,
            Set<String> matchingItemIds, Set<String> fullBoxCategories, List<String> itemOrder) {
        ContainerWithdrawalResult result = startClassificationBatch(target, matchingItemIds);
        if (result.state() == ContainerTransferState.PENDING) {
            active.batchPolicy = new ClassificationBatchPolicy(fullBoxCategories, itemOrder);
        }
        return result;
    }

    private ContainerWithdrawalResult startWithdrawal(
            ContainerTarget target, Set<String> matchingItemIds, boolean classificationBatch) {
        Objects.requireNonNull(target, "target");
        Set<String> itemIds = checkedItemIds(matchingItemIds);
        if (!scheduler.isClientThread()) {
            return ContainerWithdrawalResult.failed(
                    "缓存取出必须在客户端线程执行",
                    Map.of(),
                    false
            );
        }
        if (itemIds.isEmpty()) {
            return ContainerWithdrawalResult.succeeded(
                    "没有需要从缓存取出的物品",
                    Map.of(),
                    false
            );
        }
        if (active != null) {
            return ContainerWithdrawalResult.failed(
                    "已有缓存取出操作正在进行",
                    Map.of(),
                    false
            );
        }
        active = new ActiveWithdrawal(target, itemIds, classificationBatch);
        if (target.partner() != null) {
            active.visit = new MinecraftContainerVisit(minecraft, navigation, target);
        }
        active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        return ContainerWithdrawalResult.pending("正在打开缓存容器");
    }

    @Override
    public ContainerWithdrawalResult pollWithdrawal() {
        if (!scheduler.isClientThread()) {
            return ContainerWithdrawalResult.failed(
                    "缓存取出必须在客户端线程执行",
                    Map.of(),
                    false
            );
        }
        if (active == null) {
            return ContainerWithdrawalResult.failed(
                    "当前没有缓存取出操作",
                    Map.of(),
                    false
            );
        }
        if (active.visit == null && Instant.now().isAfter(active.deadline)) {
            return fail("缓存取出超时");
        }
        return switch (active.phase) {
            case OPENING -> pollOpening();
            case READY -> pollReady();
            case CONFIRMING -> pollConfirmation();
        };
    }

    @Override
    public void cancelWithdrawal() {
        if (scheduler.isClientThread()) {
            if (active != null && active.visit != null) {
                active.visit.cancelNavigation();
            }
            closeContainer();
        }
        active = null;
    }

    private ContainerWithdrawalResult pollOpening() {
        if (active.visit != null) {
            TaskModuleResult visit = active.visit.tick();
            if (visit.state() == TaskModuleState.FAILED) {
                return fail(visit.message());
            }
            if (visit.state() != TaskModuleState.SUCCEEDED) {
                return ContainerWithdrawalResult.pending(visit.message());
            }
            active.visit = null;
            active.openSent = true;
            active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        }
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen != null) {
            if (!active.openSent) {
                closeContainer();
                return ContainerWithdrawalResult.pending("正在关闭当前界面");
            }
            AbstractContainerMenu menu = screen.getMenu();
            if (!MinecraftContainerInteractions.ready(menu) || playerSlots(menu).isEmpty()
                    || containerSlots(menu).isEmpty()) {
                return ContainerWithdrawalResult.pending("等待容器槽位加载");
            }
            active.phase = Phase.READY;
            active.deadline = Instant.now().plus(WITHDRAW_TIMEOUT);
            return pollReady();
        }
        if (!active.openSent) {
            openBlock(active.target);
            active.openSent = true;
            active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        }
        return ContainerWithdrawalResult.pending("等待缓存容器界面");
    }

    private ContainerWithdrawalResult pollReady() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            return fail("缓存容器在取出前关闭");
        }
        AbstractContainerMenu menu = screen.getMenu();
        Candidate candidate = findCandidate(menu, active);
        if (candidate == null) {
            boolean remaining = containerHasMatching(
                    menu,
                    active.matchingItemIds
            );
            String message = !remaining ? "缓存中没有可分类物品"
                    : active.classificationBatch && !active.withdrawn.isEmpty()
                            ? "本批取出完成，缓存仍有待分类物品"
                            : "玩家背包空间不足，稍后继续";
            return complete(message, remaining);
        }
        active.phase = Phase.CONFIRMING;
        active.pendingItemId = candidate.itemId();
        active.pendingCount = 0;
        active.playerBefore = playerItemCount(menu, candidate.itemId());
        active.containerBefore = containerItemCount(menu, candidate.itemId());
        active.confirmationPolls = 0;
        active.deadline = Instant.now().plus(WITHDRAW_TIMEOUT);
        var batch = new ContainerInteractionBatch(candidate.itemId());
        while (candidate != null && batch.accepts(candidate.itemId(), 1)) {
            active.pendingCount += candidate.count();
            click(menu, candidate.slot(), ContainerInput.QUICK_MOVE);
            batch.record(candidate.count(), 1);
            // Reserve classification capacity immediately; the ledger is booked on confirmation.
            active.batchPolicy.recordConfirmed(candidate.itemId());
            candidate = findCandidate(menu, active, active.pendingItemId);
        }
        return ContainerWithdrawalResult.pending("等待服务器确认取出");
    }

    private ContainerWithdrawalResult pollConfirmation() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            return fail("缓存容器在确认取出前关闭");
        }
        AbstractContainerMenu menu = screen.getMenu();
        active.confirmationPolls++;
        int moved = active.pendingCount;
        int playerAfter = playerItemCount(menu, active.pendingItemId);
        int containerAfter = containerItemCount(menu, active.pendingItemId);
        boolean confirmed = playerAfter == active.playerBefore + moved
                && containerAfter == active.containerBefore - moved;
        if (active.confirmationPolls >= CONFIRM_POLLS
                && confirmed
                && menu.getCarried().isEmpty()) {
            active.withdrawn.merge(
                    active.pendingItemId,
                    moved,
                    Math::addExact
            );
            active.phase = Phase.READY;
            active.deadline = Instant.now().plus(WITHDRAW_TIMEOUT);
            active.pendingItemId = "";
            active.pendingCount = 0;
            return pollReady();
        }
        return ContainerWithdrawalResult.pending("等待服务器确认取出");
    }

    private ContainerWithdrawalResult complete(
            String message,
            boolean matchingItemsRemain
    ) {
        Map<String, Integer> withdrawn = Map.copyOf(active.withdrawn);
        closeContainer();
        active = null;
        return ContainerWithdrawalResult.succeeded(
                message,
                withdrawn,
                matchingItemsRemain
        );
    }

    private ContainerWithdrawalResult fail(String message) {
        Map<String, Integer> withdrawn = active == null
                ? Map.of()
                : Map.copyOf(active.withdrawn);
        closeContainer();
        active = null;
        return ContainerWithdrawalResult.failed(
                message,
                withdrawn,
                false
        );
    }

    private static Candidate findCandidate(
            AbstractContainerMenu menu,
            ActiveWithdrawal batch
    ) {
        return findCandidate(menu, batch, "");
    }

    private static Candidate findCandidate(AbstractContainerMenu menu, ActiveWithdrawal batch,
            String exactKey) {
        if (batch.classificationBatch && !batch.batchPolicy.hasCapacity()) {
            return null;
        }
        Candidate best = null;
        int fewestStacks = Integer.MAX_VALUE;
        for (int slotIndex : containerSlots(menu)) {
            Slot slot = menu.getSlot(slotIndex);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String itemId = itemId(stack);
            if (!exactKey.isEmpty() && !exactKey.equals(itemId)) continue;
            if (!ItemIdentity.matches(itemId, batch.matchingItemIds)) {
                continue;
            }
            if (batch.classificationBatch && !batch.batchPolicy.accepts(itemId)) {
                continue;
            }
            if (playerCapacity(menu, stack) < stack.getCount()) {
                continue;
            }
            Candidate candidate = new Candidate(slotIndex, itemId, stack.getCount());
            if (!batch.classificationBatch) {
                return candidate;
            }
            int taken = batch.batchPolicy.priority(itemId);
            if (taken < fewestStacks) {
                best = candidate;
                fewestStacks = taken;
            }
        }
        return best;
    }

    private static int playerCapacity(
            AbstractContainerMenu menu,
            ItemStack stack
    ) {
        int capacity = 0;
        int reservedEmptySlots = 2;
        for (int slotIndex : playerSlots(menu)) {
            Slot slot = menu.getSlot(slotIndex);
            if (!slot.mayPlace(stack)) {
                continue;
            }
            ItemStack current = slot.getItem();
            int maximum = slot.getMaxStackSize(stack);
            if (current.isEmpty()) {
                if (reservedEmptySlots-- > 0) {
                    continue;
                }
                capacity = Math.addExact(capacity, maximum);
            } else if (ItemStack.isSameItemSameComponents(current, stack)) {
                capacity = Math.addExact(
                        capacity,
                        Math.max(0, maximum - current.getCount())
                );
            }
        }
        return capacity;
    }

    private static boolean containerHasMatching(
            AbstractContainerMenu menu,
            Set<String> matchingItemIds
    ) {
        for (int slotIndex : containerSlots(menu)) {
            ItemStack stack = menu.getSlot(slotIndex).getItem();
            if (!stack.isEmpty() && dev.stowling.automation.ItemIdentity.matches(itemId(stack), matchingItemIds)) {
                return true;
            }
        }
        return false;
    }

    private static List<Integer> playerSlots(AbstractContainerMenu menu) {
        return MinecraftContainerInteractions.slots(menu, true);
    }

    private static List<Integer> containerSlots(AbstractContainerMenu menu) {
        return MinecraftContainerInteractions.slots(menu, false);
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
            ContainerInput input
    ) {
        MinecraftContainerInteractions.click(minecraft, menu, slot, 0, input);
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

    private static Set<String> checkedItemIds(Set<String> values) {
        Objects.requireNonNull(values, "matchingItemIds");
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            String itemId = value == null ? "" : value.trim();
            if (!itemId.isEmpty()) {
                result.add(itemId);
            }
        }
        return Set.copyOf(result);
    }

    private enum Phase {
        OPENING,
        READY,
        CONFIRMING
    }

    private record Candidate(int slot, String itemId, int count) {
    }

    private static final class ActiveWithdrawal {
        private MinecraftContainerVisit visit;
        private final ContainerTarget target;
        private final Set<String> matchingItemIds;
        private final boolean classificationBatch;
        private final Map<String, Integer> withdrawn = new LinkedHashMap<>();
        private ClassificationBatchPolicy batchPolicy = new ClassificationBatchPolicy(Set.of());
        private Phase phase = Phase.OPENING;
        private Instant deadline;
        private boolean openSent;
        private String pendingItemId = "";
        private int pendingCount;
        private int playerBefore;
        private int containerBefore;
        private int confirmationPolls;

        private ActiveWithdrawal(
                ContainerTarget target,
                Set<String> matchingItemIds,
                boolean classificationBatch
        ) {
            this.target = target;
            this.matchingItemIds = matchingItemIds;
            this.classificationBatch = classificationBatch;
        }
    }
}
