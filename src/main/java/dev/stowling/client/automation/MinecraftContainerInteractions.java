package dev.stowling.client.automation;

import dev.stowling.automation.ContainerClickPlan;
import dev.stowling.automation.ContainerSyncTracker;
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

    private MinecraftContainerInteractions() {
    }

    public static void contentsInitialized(AbstractContainerMenu menu) {
        SYNC.initialized(menu);
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
        if (minecraft.player == null || minecraft.gameMode == null
                || minecraft.player.containerMenu != menu) {
            throw new IllegalStateException("容器交互会话已失效");
        }
        minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, input,
                minecraft.player);
    }

    public static int moveExact(Minecraft minecraft, AbstractContainerMenu menu,
            int source, int destination, int amount) {
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
