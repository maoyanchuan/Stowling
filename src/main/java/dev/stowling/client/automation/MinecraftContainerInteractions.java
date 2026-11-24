package dev.stowling.client.automation;

import dev.stowling.automation.ContainerClickPlan;
import dev.stowling.automation.ContainerSyncTracker;
import dev.stowling.v2.runtime.TaskTrace;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Shared container I/O primitives for task, packing, inspection, and future client modules. */
public final class MinecraftContainerInteractions {
    private static final ContainerSyncTracker SYNC = new ContainerSyncTracker();

    private static java.util.function.Supplier<java.util.Set<Integer>> locks = java.util.Set::of;
    private static final java.util.Map<AbstractContainerMenu, List<ItemStack>> SERVER =
            new java.util.WeakHashMap<>();

    public static void configureLocks(java.util.function.Supplier<java.util.Set<Integer>> source) {
        locks = source;
    }

    public static boolean locked(int inventoryIndex) {
        return locks.get().contains(inventoryIndex);
    }

    public static int inventoryIndex(AbstractContainerMenu menu, int index) {
        if (index < 0 || index >= menu.slots.size()) return -1;
        var player = Minecraft.getInstance().player;
        var slot = menu.getSlot(index);
        if (player == null || slot.container != player.getInventory()) return -1;
        int inventoryIndex = slot.getContainerSlot();
        return inventoryIndex >= 0 && inventoryIndex < 36 ? inventoryIndex : -1;
    }

    public static boolean movable(AbstractContainerMenu menu, int index) {
        int inventoryIndex = inventoryIndex(menu, index);
        return inventoryIndex >= 0 && !locked(inventoryIndex);
    }

    public static List<Integer> transferSlots(AbstractContainerMenu menu, boolean player) {
        return slots(menu, player).stream()
                .filter(index -> !player || movable(menu, index)).toList();
    }

    public static long generation(AbstractContainerMenu menu) {
        return SYNC.generation(menu);
    }

    public static boolean confirmed(AbstractContainerMenu menu, Object batchMenu, long generation) {
        return SYNC.confirmed(menu, batchMenu, generation);
    }

    public static java.util.Map<String, Integer> serverCounts(AbstractContainerMenu menu,
            List<Integer> indices) {
        var stacks = SERVER.get(menu);
        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        if (stacks == null) throw new IllegalStateException("等待服务器完整容器快照");
        for (int index : indices) {
            var stack = stacks.get(index);
            if (!stack.isEmpty()) {
                counts.merge(MinecraftItemIdentity.key(stack), stack.getCount(), Math::addExact);
            }
        }
        return counts;
    }

    /** A forced state mismatch requests a full server response to this exact quick move. */
    public static void quickMoveConfirmed(Minecraft minecraft, AbstractContainerMenu menu,
            int source) {
        requireSession(minecraft, menu);
        if (!movable(menu, source) || !menu.getSlot(source).mayPickup(minecraft.player)
                || !menu.getCarried().isEmpty()) {
            throw new IllegalStateException("普通存入只能搬运未锁定主背包格");
        }
        TaskTrace.event("CALL", "deposit.quickMove", "sourceSlot=" + source
                + "; inventoryIndex=" + inventoryIndex(menu, source)
                + "; lockedSlots=" + locks.get() + "; generation=" + generation(menu), 0, false);
        // QUICK_MOVE with an empty changed-slot map and impossible state ID is accepted by
        // vanilla 26.2, which then broadcasts full state. No client stability heuristic is used.
        menu.clicked(source, 0, ContainerInput.QUICK_MOVE, minecraft.player);
        minecraft.player.connection.send(
                new net.minecraft.network.protocol.game.ServerboundContainerClickPacket(
                        menu.containerId, -1, (short) source, (byte) 0,
                        ContainerInput.QUICK_MOVE,
                        new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),
                        net.minecraft.network.HashedStack.EMPTY));
    }

    private static void requireSession(Minecraft minecraft, AbstractContainerMenu menu) {
        if (minecraft.player == null || minecraft.gameMode == null
                || minecraft.player.containerMenu != menu) {
            throw new IllegalStateException("容器交互会话已失效");
        }
    }

    private MinecraftContainerInteractions() {
    }

    public static void contentsInitialized(AbstractContainerMenu menu) {
        SERVER.put(menu, new ArrayList<>(menu.slots.stream()
                .map(slot -> slot.getItem().copy()).toList()));
        SYNC.initialized(menu);
    }

    public static void slotUpdated(AbstractContainerMenu menu, int index) {
        var stacks = SERVER.get(menu);
        if (stacks != null && index >= 0 && index < stacks.size()) {
            stacks.set(index, menu.getSlot(index).getItem().copy());
        }
    }

    public static boolean ready(AbstractContainerMenu menu) {
        return SYNC.ready(menu);
    }

    public static List<Integer> slots(AbstractContainerMenu menu, boolean player) {
        List<Integer> result = new ArrayList<>();
        for (int index = 0; index < menu.slots.size(); index++) {
            if ((menu.getSlot(index).container instanceof Inventory) == player) result.add(index);
        }
        return List.copyOf(result);
    }

    public static void click(Minecraft minecraft, AbstractContainerMenu menu, int slot,
            int button, ContainerInput input) {
        requireSession(minecraft, menu);
        int inventoryIndex = inventoryIndex(menu, slot);
        if (inventoryIndex >= 0 && locked(inventoryIndex)
                || input == ContainerInput.SWAP && locked(button)) {
            throw new IllegalStateException("自动搬运不能操作锁定栏位");
        }
        if (slot >= 0 && menu.getSlot(slot).container instanceof Inventory
                && inventoryIndex < 0) {
            throw new IllegalStateException("装备与副手不属于自动搬运来源");
        }
        if (input == ContainerInput.SWAP && (button < 0 || button >= 9)) {
            throw new IllegalStateException("自动换手目标必须为快捷栏");
        }
        if (input == ContainerInput.QUICK_MOVE && inventoryIndex < 0
                && !locks.get().isEmpty()) {
            moveToUnlocked(minecraft, menu, slot);
            return;
        }
        clickForUse(minecraft, menu, slot, button, input);
    }

    /** Consumption may temporarily equip locked food and must restore its original position. */
    public static void clickForUse(Minecraft minecraft, AbstractContainerMenu menu, int slot,
            int button, ContainerInput input) {
        requireSession(minecraft, menu);
        TaskTrace.event("CALL", "game.handleContainerInput", "menu=" + menu.containerId
                + "; state=" + menu.getStateId() + "; slot=" + slot + "; button=" + button
                + "; input=" + input + "; source=" + (slot >= 0 && slot < menu.slots.size()
                        ? MinecraftItemIdentity.key(menu.getSlot(slot).getItem()) + " x"
                                + menu.getSlot(slot).getItem().getCount() + "; stackLimit="
                                + menu.getSlot(slot).getItem().getMaxStackSize() : "outside"),
                0, false);
        try {
            minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, input,
                    minecraft.player);
            TaskTrace.event("RETURN", "game.handleContainerInput", "cursorCount="
                    + menu.getCarried().getCount() + "; state=" + menu.getStateId(), 0, false);
        } catch (RuntimeException failure) {
            TaskTrace.event("ERROR", "game.handleContainerInput",
                    failure.getClass().getSimpleName(), 0, false);
            throw failure;
        }
    }

    private static void moveToUnlocked(Minecraft minecraft, AbstractContainerMenu menu,
            int source) {
        for (int destination : transferSlots(menu, true)) {
            var stack = menu.getSlot(source).getItem();
            if (stack.isEmpty()) break;
            var to = menu.getSlot(destination);
            var existing = to.getItem();
            if (!to.mayPlace(stack) || !existing.isEmpty()
                    && !ItemStack.isSameItemSameComponents(existing, stack)) continue;
            int amount = Math.min(stack.getCount(), to.getMaxStackSize(stack) - existing.getCount());
            if (amount > 0) moveExact(minecraft, menu, source, destination, amount);
        }
    }

    public static int moveExact(Minecraft minecraft, AbstractContainerMenu menu,
            int source, int destination, int amount) {
        if (inventoryIndex(menu, source) >= 0 && !movable(menu, source)
                || inventoryIndex(menu, destination) >= 0 && !movable(menu, destination)) {
            throw new IllegalStateException("自动搬运不能操作锁定栏位");
        }
        var from = menu.getSlot(source);
        var to = menu.getSlot(destination);
        ItemStack stack = from.getItem();
        ItemStack existing = to.getItem();
        if (!menu.getCarried().isEmpty() || stack.isEmpty() || amount > stack.getCount()
                || !from.mayPickup(minecraft.player) || !to.mayPlace(stack)
                || !existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack)
                || to.getMaxStackSize(stack) - existing.getCount() < amount
                || amount < stack.getCount() && !from.mayPlace(stack)) {
            throw new IllegalStateException("当前槽位不能执行精确搬运");
        }
        var plan = ContainerClickPlan.exact(stack.getCount(), amount);
        for (var step : plan) {
            click(minecraft, menu, step.source() ? source : destination, step.button(),
                    ContainerInput.PICKUP);
        }
        return plan.size();
    }
}
