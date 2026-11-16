package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.client.interaction.ClientActionScheduler;
import java.time.Duration;
import java.time.Instant;
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
    private static final int CONFIRM_POLLS = 2;

    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private ActiveDeposit active;

    public MinecraftContainerTransferPort(
            Minecraft minecraft,
            ClientActionScheduler scheduler
    ) {
        this.minecraft = Objects.requireNonNull(minecraft, "minecraft");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
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
        active.deadline = Instant.now().plus(OPEN_TIMEOUT);
        return ContainerTransferResult.pending("正在打开容器", remaining);
    }

    @Override
    public ContainerTransferResult pollDeposit() {
        if (!scheduler.isClientThread()) {
            return fail("容器存入必须在客户端线程执行");
        }
        if (active == null) {
            return ContainerTransferResult.failed(
                    "当前没有容器存入操作",
                    Map.of()
            );
        }
        if (Instant.now().isAfter(active.deadline)) {
            return fail("容器操作超时");
        }
        return switch (active.phase) {
            case OPENING -> pollOpening();
            case READY -> pollReady();
            case CONFIRMING -> pollConfirmation();
        };
    }

    @Override
    public void cancelDeposit() {
        if (scheduler.isClientThread()) {
            closeContainer();
        }
        active = null;
    }

    private ContainerTransferResult pollOpening() {
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
        Candidate candidate = findCandidate(menu, active.remaining);
        if (candidate == null) {
            if (hasRequestedStack(menu, active.remaining)) {
                return succeed("当前容器容量不足，仍有物品待存入");
            }
            return fail("当前容器没有可安全转移的完整物品堆");
        }
        active.phase = Phase.CONFIRMING;
        active.pendingItemId = candidate.itemId();
        active.pendingCount = candidate.count();
        active.playerBefore = playerItemCount(
                menu,
                candidate.itemId()
        );
        active.containerBefore = containerItemCount(
                menu,
                candidate.itemId()
        );
        active.confirmationPolls = 0;
        active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
        click(menu, candidate.slot(), ContainerInput.QUICK_MOVE);
        return ContainerTransferResult.pending(
                "等待服务器确认存入",
                remaining()
        );
    }

    private ContainerTransferResult pollConfirmation() {
        AbstractContainerScreen<?> screen = currentContainerScreen();
        if (screen == null) {
            return fail("容器在确认存入前关闭");
        }
        AbstractContainerMenu menu = screen.getMenu();
        active.confirmationPolls++;
        int moved = active.pendingCount;
        int playerAfter = playerItemCount(menu, active.pendingItemId);
        int containerAfter = containerItemCount(menu, active.pendingItemId);
        boolean countsConfirmed = playerAfter == active.playerBefore - moved
                && containerAfter == active.containerBefore + moved;
        if (active.confirmationPolls >= CONFIRM_POLLS
                && countsConfirmed
                && menu.getCarried().isEmpty()) {
            decrement(active.remaining, active.pendingItemId, moved);
            active.phase = Phase.READY;
            active.deadline = Instant.now().plus(TRANSFER_TIMEOUT);
            active.pendingItemId = "";
            active.pendingCount = 0;
            if (active.remaining.isEmpty()) {
                return complete();
            }
            return ContainerTransferResult.pending(
                    "已存入 " + moved + " 个物品",
                    remaining()
            );
        }
        return ContainerTransferResult.pending(
                "等待服务器确认存入",
                remaining()
        );
    }

    private ContainerTransferResult complete() {
        return succeed("拾取物品已存入容器");
    }

    private ContainerTransferResult succeed(String message) {
        Map<String, Integer> remaining = remaining();
        closeContainer();
        active = null;
        return ContainerTransferResult.succeeded(message, remaining);
    }

    private ContainerTransferResult fail(String message) {
        Map<String, Integer> remaining = remaining();
        closeContainer();
        active = null;
        return ContainerTransferResult.failed(message, remaining);
    }

    private Map<String, Integer> remaining() {
        return active == null ? Map.of() : Map.copyOf(active.remaining);
    }

    private Candidate findCandidate(
            AbstractContainerMenu menu,
            Map<String, Integer> remaining
    ) {
        for (int slotIndex : playerSlots(menu)) {
            Slot slot = menu.getSlot(slotIndex);
            ItemStack stack = slot.getItem();
            if (!matchesRequest(stack, remaining)) {
                continue;
            }
            if (destinationCapacity(menu, stack) < stack.getCount()) {
                continue;
            }
            return new Candidate(
                    slotIndex,
                    itemId(stack),
                    stack.getCount()
            );
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
        int wanted = remaining.getOrDefault(itemId(stack), 0);
        return wanted > 0 && stack.getCount() <= wanted;
    }

    private static int destinationCapacity(
            AbstractContainerMenu menu,
            ItemStack stack
    ) {
        int capacity = 0;
        for (int slotIndex : containerSlots(menu)) {
            Slot slot = menu.getSlot(slotIndex);
            if (!slot.mayPlace(stack)) {
                continue;
            }
            ItemStack current = slot.getItem();
            int maximum = slot.getMaxStackSize(stack);
            if (current.isEmpty()) {
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
        if (player == null || gameMode == null) {
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
        LocalPlayer player = minecraft.player;
        MultiPlayerGameMode gameMode = minecraft.gameMode;
        if (player != null && gameMode != null) {
            gameMode.handleContainerInput(
                    menu.containerId,
                    slot,
                    0,
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
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
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
        CONFIRMING
    }

    private record Candidate(int slot, String itemId, int count) {
    }

    private static final class ActiveDeposit {
        private final ContainerTarget target;
        private final Map<String, Integer> remaining;
        private Phase phase = Phase.OPENING;
        private Instant deadline;
        private boolean openSent;
        private String pendingItemId = "";
        private int pendingCount;
        private int playerBefore;
        private int containerBefore;
        private int confirmationPolls;

        private ActiveDeposit(
                ContainerTarget target,
                Map<String, Integer> remaining
        ) {
            this.target = target;
            this.remaining = remaining;
        }
    }
}
