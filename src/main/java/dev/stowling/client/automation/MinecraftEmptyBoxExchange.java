package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/** Exchanges one fully owned stack for an empty box when the player's inventory is full. */
final class MinecraftEmptyBoxExchange implements TaskModuleExecutor {
    private final Minecraft minecraft;
    private final TaskExecutionContext context;
    private final ContainerTarget supply;
    private MinecraftContainerVisit visit;
    private ItemStack box = ItemStack.EMPTY;
    private String stashedKey;
    private int stashedCount;
    private StorageTransferConfirmation removed;
    private StorageTransferConfirmation acquired;
    private long pickupsBefore;
    private long boxPickupsBefore;
    private boolean opened;
    private boolean sent;
    private boolean reread;
    private boolean stop;
    private int settle;
    private TaskModuleResult terminal;

    MinecraftEmptyBoxExchange(Minecraft minecraft, TaskExecutionContext context,
            ContainerTarget supply) {
        this.minecraft = minecraft;
        this.context = context;
        this.supply = supply;
        visit = new MinecraftContainerVisit(minecraft, context.navigation(), supply);
    }

    ItemStack box() {
        return box.copy();
    }

    Map<String, Integer> stashed() {
        return Map.of(stashedKey, stashedCount);
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (terminal != null) return terminal;
        if (stop && !sent) return finish(false, "取空盒前已停止");
        if (!opened) {
            TaskModuleResult result = visit.tick();
            if (result.state() == TaskModuleState.FAILED) {
                if (!sent) return finish(false, result.message());
                visit = new MinecraftContainerVisit(minecraft, context.navigation(), supply);
                return TaskModuleResult.waiting("空盒交换已发送，等待重新读取供应箱确认");
            }
            if (result.state() != TaskModuleState.SUCCEEDED) return result;
            opened = true;
        }
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)
                || !MinecraftContainerInteractions.ready(screen.getMenu())) {
            return TaskModuleResult.waiting("等待空盒供应箱同步");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (!sent) return exchange(menu);
        if (++settle < 2 || !menu.getCarried().isEmpty()) {
            return TaskModuleResult.waiting("等待空盒交换确认");
        }
        String boxKey = MinecraftItemIdentity.key(box);
        boolean confirmed = removed.matches(count(menu, true, stashedKey),
                MinecraftInventoryPickups.count(minecraft, stashedKey) - pickupsBefore,
                true, () -> count(menu, false, stashedKey))
                && acquired.matches(count(menu, true, boxKey),
                        MinecraftInventoryPickups.count(minecraft, boxKey) - boxPickupsBefore,
                        true, () -> count(menu, false, boxKey));
        if (!reread) {
            minecraft.player.closeContainer();
            visit = new MinecraftContainerVisit(minecraft, context.navigation(), supply);
            opened = false;
            reread = true;
            settle = 0;
            return TaskModuleResult.waiting("重新读取供应箱，确认暂存物品与空盒交换");
        }
        if (!confirmed && !removed.matchesServerContainer(true,
                count(menu, false, stashedKey))) {
            return TaskModuleResult.waiting("等待服务器确认空盒交换，暂存物品尚未记账");
        }
        if (count(menu, true, boxKey) < acquired.expectedPlayer()) {
            return TaskModuleResult.waiting("等待空潜影盒进入背包");
        }
        context.workspace().consumeDeposit("managed", stashed(), Map.of());
        context.inventory().recordWithdrawn(context.workspace().inventoryAccount(),
                Map.of(boxKey, 1));
        return finish(true, "空盒交换已确认，装盒后取回暂存物品");
    }

    private TaskModuleResult exchange(AbstractContainerMenu menu) {
        if (!menu.getCarried().isEmpty()) return TaskModuleResult.waiting("等待鼠标物品归位");
        Map<String, Integer> owned = context.workspace().managedItems();
        for (int supplySlot : MinecraftContainerInteractions.slots(menu, false)) {
            var from = menu.getSlot(supplySlot);
            ItemStack empty = from.getItem();
            if (empty.getCount() != 1 || !MinecraftItemIdentity.isBox(empty)
                    || !MinecraftItemIdentity.contents(empty)
                    .nonEmptyItemCopyStream().findAny().isEmpty()
                    || !from.mayPickup(minecraft.player)) continue;
            for (int playerSlot : MinecraftContainerInteractions.slots(menu, true)) {
                var to = menu.getSlot(playerSlot);
                ItemStack stack = to.getItem();
                if (stack.isEmpty()) continue;
                String key = MinecraftItemIdentity.key(stack);
                if (owned.getOrDefault(key, 0) < stack.getCount()
                        || !to.mayPickup(minecraft.player) || !from.mayPlace(stack)
                        || !to.mayPlace(empty) || from.getMaxStackSize(stack) < stack.getCount()) {
                    continue;
                }
                box = empty.copyWithCount(1);
                stashedKey = key;
                stashedCount = stack.getCount();
                removed = StorageTransferConfirmation.exact(count(menu, true, key),
                        count(menu, false, key), -stashedCount);
                String boxKey = MinecraftItemIdentity.key(box);
                acquired = StorageTransferConfirmation.emptyBoxSupply(count(menu, true, boxKey));
                pickupsBefore = MinecraftInventoryPickups.count(minecraft, key);
                boxPickupsBefore = MinecraftInventoryPickups.count(minecraft, boxKey);
                sent = true;
                MinecraftContainerInteractions.click(minecraft, menu, supplySlot, 0,
                        ContainerInput.PICKUP);
                MinecraftContainerInteractions.click(minecraft, menu, playerSlot, 0,
                        ContainerInput.PICKUP);
                MinecraftContainerInteractions.click(minecraft, menu, supplySlot, 0,
                        ContainerInput.PICKUP);
                return TaskModuleResult.waiting("暂存一组任务物品以取出空潜影盒");
            }
        }
        return finish(false, "空盒供应箱无法交换任务物品，请补充空盒或腾出背包空间");
    }

    private static int count(AbstractContainerMenu menu, boolean player, String key) {
        return MinecraftContainerInteractions.slots(menu, player).stream()
                .map(index -> menu.getSlot(index).getItem())
                .filter(stack -> !stack.isEmpty() && MinecraftItemIdentity.key(stack).equals(key))
                .mapToInt(ItemStack::getCount).sum();
    }

    private TaskModuleResult finish(boolean success, String message) {
        terminal = success ? TaskModuleResult.succeeded(message) : TaskModuleResult.failed(message);
        visit.cancelNavigation();
        if (minecraft.player != null && minecraft.player.containerMenu.getCarried().isEmpty()) {
            minecraft.player.closeContainer();
        }
        return terminal;
    }

    @Override
    public void requestStop() {
        stop = true;
    }

    @Override
    public boolean safeToStop() {
        return !sent || terminal != null;
    }

    @Override
    public void cancel() {
        requestStop();
    }
}
