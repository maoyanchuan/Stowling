package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Navigation, exact component-aware transfers, and acknowledgement at one container. */
final class MinecraftStorageAccess implements TaskModuleExecutor {
    private final Minecraft minecraft;
    private final TaskExecutionContext context;
    private final ContainerTarget target;
    private final Map<String, Integer> remaining;
    private final boolean withdraw;
    private final boolean inspect;
    private final List<ItemStack> contents = new ArrayList<>();
    private boolean navigating;
    private boolean sent;
    private boolean stop;
    private boolean done;
    private int age;
    private int settle;
    private String pendingKey;
    private int pendingCount;
    private int playerBefore;
    private int containerBefore;
    private TaskModuleResult result;

    MinecraftStorageAccess(Minecraft minecraft, TaskExecutionContext context,
            ContainerTarget target, Map<String, Integer> requested, boolean withdraw,
            boolean inspect) {
        this.minecraft = minecraft;
        this.context = context;
        this.target = target;
        this.remaining = new LinkedHashMap<>(requested);
        this.withdraw = withdraw;
        this.inspect = inspect;
    }

    List<ItemStack> contents() {
        return contents.stream().map(ItemStack::copy).toList();
    }

    Map<String, Integer> remaining() {
        return Map.copyOf(remaining);
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (done) {
            return result;
        }
        if (minecraft.player == null || minecraft.gameMode == null || minecraft.level == null) {
            return finish(false, "当前世界不可用，物品归属已保留");
        }
        if (++age > 2400) {
            if (!safeToStop()) {
                return TaskModuleResult.waiting("存取确认超时，等待在途物品确认后收尾");
            }
            return finish(false, "容器存取超时");
        }
        if (stop && pendingKey == null) {
            return finish(true, "容器操作已安全停止");
        }
        if (!sent) {
            if (minecraft.gui.screen() != null) {
                if (!minecraft.player.containerMenu.getCarried().isEmpty()) {
                    return TaskModuleResult.waiting("等待鼠标上的物品归位");
                }
                minecraft.player.closeContainer();
                return TaskModuleResult.waiting("正在关闭上一容器");
            }
            NavigationStatus status = navigating
                    ? context.navigation().status(target.position())
                    : context.navigation().startNear(target.position(), 2, target.standingArea());
            navigating = true;
            if (status == NavigationStatus.FAILED) {
                return finish(false, "无法到达容器操作位置 " + target.position());
            }
            if (status != NavigationStatus.ARRIVED) {
                return TaskModuleResult.waiting("正在前往容器 " + target.position());
            }
            if (!ContainerStandingAccess.canOpen(minecraft.player, target)) {
                return finish(false, "容器不在可交互距离内");
            }
            BlockPos position = block(target);
            minecraft.gameMode.useItemOn(minecraft.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false));
            sent = true;
            settle = 0;
            return TaskModuleResult.waiting("等待容器打开");
        }
        if (!(minecraft.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            if (pendingKey != null) {
                return TaskModuleResult.waiting("等待容器同步，在途存取尚未确认");
            }
            if (age > 240) {
                return finish(false, "容器未打开或被关闭");
            }
            return TaskModuleResult.waiting("等待容器界面");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (++settle < 4) {
            return TaskModuleResult.waiting("等待容器槽位同步");
        }
        if (pendingKey != null) {
            int sign = withdraw ? 1 : -1;
            if (count(menu, true, pendingKey) != playerBefore + sign * pendingCount
                    || count(menu, false, pendingKey) != containerBefore - sign * pendingCount
                    || !menu.getCarried().isEmpty()) {
                return TaskModuleResult.waiting("等待服务器确认精确存取");
            }
            if (withdraw) {
                context.inventory().recordWithdrawn(context.workspace().inventoryAccount(),
                        Map.of(pendingKey, pendingCount));
            } else {
                context.workspace().consumeDeposit("managed", Map.of(pendingKey, pendingCount),
                        Map.of());
            }
            remaining.compute(pendingKey, (key, value) -> Math.max(0, value - pendingCount));
            pendingKey = null;
            age = 0;
            if (stop) {
                return finish(true, "精确存取已确认，可安全停止");
            }
        }
        contents.clear();
        slots(menu, false).forEach(index -> contents.add(menu.getSlot(index).getItem().copy()));
        if (inspect) {
            return finish(true, "已读取容器内容");
        }
        for (int index : slots(menu, !withdraw)) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (stack.isEmpty()) {
                continue;
            }
            String key = MinecraftItemIdentity.key(stack);
            int wanted = remaining.getOrDefault(key, 0);
            if (!withdraw) {
                wanted = Math.min(wanted, context.workspace().managedItems().getOrDefault(key, 0));
            }
            if (wanted <= 0) {
                continue;
            }
            for (int destination : slots(menu, withdraw)) {
                Slot slot = menu.getSlot(destination);
                ItemStack current = slot.getItem();
                if (!slot.mayPlace(stack) || !current.isEmpty()
                        && !ItemStack.isSameItemSameComponents(current, stack)) {
                    continue;
                }
                int capacity = slot.getMaxStackSize(stack)
                        - (current.isEmpty() ? 0 : current.getCount());
                int amount = Math.min(Math.min(wanted, stack.getCount()), capacity);
                if (amount <= 0) {
                    continue;
                }
                pendingKey = key;
                pendingCount = amount;
                playerBefore = count(menu, true, key);
                containerBefore = count(menu, false, key);
                boolean wholeStack = amount == stack.getCount();
                click(menu, index, 0, ContainerInput.PICKUP);
                if (wholeStack) {
                    click(menu, destination, 0, ContainerInput.PICKUP);
                } else {
                    for (int item = 0; item < amount; item++) {
                        click(menu, destination, 1, ContainerInput.PICKUP);
                    }
                    click(menu, index, 0, ContainerInput.PICKUP);
                }
                settle = 0;
                return TaskModuleResult.waiting("正在精确" + (withdraw ? "取出" : "存入"));
            }
        }
        return finish(true, "容器存取已完成");
    }

    private TaskModuleResult finish(boolean success, String message) {
        done = true;
        if (minecraft.player != null && minecraft.player.containerMenu.getCarried().isEmpty()) {
            minecraft.player.closeContainer();
        }
        result = success ? TaskModuleResult.succeeded(message) : TaskModuleResult.failed(message);
        return result;
    }

    @Override
    public void requestStop() {
        stop = true;
    }

    @Override
    public boolean safeToStop() {
        return pendingKey == null && (minecraft.player == null
                || minecraft.player.containerMenu.getCarried().isEmpty());
    }

    @Override
    public void cancel() {
        requestStop();
        if (safeToStop() && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    static BlockPos block(ContainerTarget target) {
        return new BlockPos(target.position().x(), target.position().y(), target.position().z());
    }

    static List<Integer> slots(AbstractContainerMenu menu, boolean player) {
        return menu.slots.stream()
                .filter(slot -> (slot.container instanceof Inventory) == player)
                .map(menu.slots::indexOf).toList();
    }

    private static int count(AbstractContainerMenu menu, boolean player, String key) {
        return slots(menu, player).stream().map(menu::getSlot).map(Slot::getItem)
                .filter(stack -> !stack.isEmpty() && MinecraftItemIdentity.key(stack).equals(key))
                .mapToInt(ItemStack::getCount).sum();
    }

    private void click(AbstractContainerMenu menu, int slot, int button, ContainerInput input) {
        minecraft.gameMode.handleContainerInput(menu.containerId, slot, button, input,
                minecraft.player);
    }
}
