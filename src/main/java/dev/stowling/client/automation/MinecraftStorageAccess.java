package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerClickPlan;
import dev.stowling.automation.ContainerInteractionBatch;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Navigation, exact component-aware transfers, and acknowledgement at one container. */
final class MinecraftStorageAccess implements TaskModuleExecutor {
    private final Minecraft minecraft;
    private final TaskExecutionContext context;
    private final ContainerTarget target;
    private MinecraftContainerVisit visit;
    private final Map<String, Integer> remaining;
    private final boolean withdraw;
    private final boolean inspect;
    private final boolean emptyBoxSupply;
    private final List<ItemStack> contents = new ArrayList<>();
    private boolean opened;
    private boolean stop;
    private boolean done;
    private int age;
    private int settle;
    private String pendingKey;
    private int pendingCount;
    private final Map<String, Integer> pendingMoves = new LinkedHashMap<>();
    private final Map<String, StorageTransferBatchConfirmation.Before> pendingBefore =
            new LinkedHashMap<>();
    private long pendingStartedAt;
    private boolean rereading;
    private boolean serverReread;
    private StorageTransferBatchConfirmation pendingConfirmation;
    private TaskModuleResult result;

    MinecraftStorageAccess(Minecraft minecraft, TaskExecutionContext context,
            ContainerTarget target, Map<String, Integer> requested, boolean withdraw,
            boolean inspect) {
        this(minecraft, context, target, requested, withdraw, inspect, false);
    }

    static MinecraftStorageAccess withdrawEmptyBox(Minecraft minecraft, TaskExecutionContext context,
            ContainerTarget supply, String key) {
        return new MinecraftStorageAccess(minecraft, context, supply, Map.of(key, 1),
                true, false, true);
    }

    private MinecraftStorageAccess(Minecraft minecraft, TaskExecutionContext context,
            ContainerTarget target, Map<String, Integer> requested, boolean withdraw,
            boolean inspect, boolean emptyBoxSupply) {
        this.minecraft = minecraft;
        this.context = context;
        this.target = target;
        this.visit = new MinecraftContainerVisit(minecraft, context.navigation(), target);
        this.remaining = new LinkedHashMap<>(requested);
        this.withdraw = withdraw;
        this.inspect = inspect;
        this.emptyBoxSupply = emptyBoxSupply;
    }

    List<ItemStack> contents() {
        return contents.stream().map(ItemStack::copy).toList();
    }

    ContainerTarget target() {
        return target;
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
        if (!opened) {
            TaskModuleResult access = visit.tick();
            if (access.state() == TaskModuleState.FAILED) {
                return finish(false, access.message());
            }
            if (access.state() != TaskModuleState.SUCCEEDED) {
                return access;
            }
            opened = true;
            serverReread = rereading;
            rereading = false;
            age = 0;
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
        if (!MinecraftContainerInteractions.ready(menu)) {
            return TaskModuleResult.waiting("等待容器完整槽位数据");
        }
        if (pendingKey != null && ++settle < 2) {
            return TaskModuleResult.waiting("等待批量存取同步");
        }
        if (pendingKey != null) {
            dev.stowling.v2.runtime.TaskTrace.event("WAIT", "storage.confirm",
                    "withdraw=" + withdraw + "; planned=" + pendingMoves
                            + "; before=" + pendingBefore
                            + "; playerNow=" + pendingMoves.keySet().stream().map(key ->
                                    key + "=" + count(menu, true, key)).toList()
                            + "; containerNow=" + pendingMoves.keySet().stream().map(key ->
                                    key + "=" + count(menu, false, key)).toList()
                            + "; pickedUp=" + pendingMoves.keySet().stream().map(key -> key + "="
                                    + (MinecraftInventoryPickups.count(minecraft, key)
                                            - pendingBefore.get(key).pickups())).toList()
                            + "; cursorEmpty=" + menu.getCarried().isEmpty(),
                    (System.nanoTime() - pendingStartedAt) / 1_000_000, true);
            boolean confirmed = pendingConfirmation.matches(key -> count(menu, true, key),
                    key -> count(menu, false, key),
                    key -> MinecraftInventoryPickups.count(minecraft, key),
                    menu.getCarried().isEmpty());
            boolean destinationConfirmed = pendingConfirmation.matchesServerContainer(
                    key -> count(menu, false, key), menu.getCarried().isEmpty());
            if (!confirmed && !serverReread && menu.getCarried().isEmpty()
                    && pendingConfirmation.hasContainerProgress(key -> count(menu, false, key))) {
                dev.stowling.v2.runtime.TaskTrace.event("RESYNC", "storage.reopen",
                        "批量存取两侧数量不一致，读取服务器快照; items=" + pendingMoves, 0, false);
                minecraft.player.closeContainer();
                visit = new MinecraftContainerVisit(minecraft, context.navigation(), target);
                opened = false;
                rereading = true;
                settle = 0;
                age = 0;
                return TaskModuleResult.waiting("立即读取原容器服务器快照，核对整批存取");
            }
            Map<String, Integer> accepted = Map.copyOf(pendingMoves);
            if (!confirmed && !(serverReread && destinationConfirmed)) {
                var observed = serverReread ? pendingConfirmation.acceptedServerSnapshot(
                        key -> count(menu, false, key), menu.getCarried().isEmpty())
                        : java.util.Optional.<Map<String, Integer>>empty();
                if (observed.isEmpty()) {
                    return TaskModuleResult.waiting(emptyBoxSupply
                            ? "等待确认空潜影盒进入背包" : "等待服务器确认精确存取");
                }
                accepted = observed.orElseThrow();
            }
            boolean partial = !accepted.equals(pendingMoves);
            if (withdraw) {
                context.inventory().recordWithdrawn(context.workspace().inventoryAccount(),
                        accepted);
            } else {
                context.workspace().consumeDeposit("managed", accepted,
                        Map.of());
                context.workspace().progress().recordDelivery(target.position(),
                        accepted);
            }
            accepted.forEach((key, amount) -> remaining.compute(key,
                    (ignored, value) -> Math.max(0, value - amount)));
            pendingKey = null;
            pendingMoves.clear();
            pendingBefore.clear();
            age = 0;
            if (partial) return finish(false, "服务器仅接受本批部分物品，已记账，剩余物品交给下一容器");
            if (stop) {
                return finish(true, "精确存取已确认，可安全停止");
            }
        }
        contents.clear();
        slots(menu, false).forEach(index -> contents.add(menu.getSlot(index).getItem().copy()));
        if (inspect) {
            return finish(true, "已读取容器内容");
        }
        Move candidate = findMove(menu, Map.of());
        if (candidate == null) return finish(true, "容器存取已完成");
        pendingKey = candidate.key();
        serverReread = false;
        pendingCount = 0;
        pendingStartedAt = System.nanoTime();
        var batch = new ContainerInteractionBatch(pendingKey, true);
        while (candidate != null && batch.accepts(candidate.key(), candidate.clicks())) {
            String key = candidate.key();
            pendingBefore.putIfAbsent(key, new StorageTransferBatchConfirmation.Before(
                    count(menu, true, key), count(menu, false, key),
                    MinecraftInventoryPickups.count(minecraft, key)));
            pendingMoves.merge(key, candidate.amount(), Math::addExact);
            pendingCount += candidate.amount();
            MinecraftContainerInteractions.moveExact(minecraft, menu, candidate.source(),
                    candidate.destination(), candidate.amount());
            batch.record(candidate.amount(), candidate.clicks());
            candidate = findMove(menu, pendingMoves);
        }
        pendingConfirmation = new StorageTransferBatchConfirmation(pendingBefore, pendingMoves,
                withdraw, emptyBoxSupply);
        settle = 0;
        return TaskModuleResult.waiting("正在批量" + (withdraw ? "取出" : "存入"));
    }

    private Move findMove(AbstractContainerMenu menu, Map<String, Integer> planned) {
        for (int source : slots(menu, !withdraw)) {
            Slot from = menu.getSlot(source);
            ItemStack stack = from.getItem();
            if (stack.isEmpty() || !from.mayPickup(minecraft.player)) continue;
            String key = MinecraftItemIdentity.key(stack);
            int wanted = remaining.getOrDefault(key, 0);
            if (!withdraw) {
                wanted = Math.min(wanted, context.workspace().managedItems().getOrDefault(key, 0));
            }
            wanted -= planned.getOrDefault(key, 0);
            if (wanted <= 0) continue;
            for (int destination : slots(menu, withdraw)) {
                Slot slot = menu.getSlot(destination);
                ItemStack current = slot.getItem();
                if (!slot.mayPlace(stack) || !current.isEmpty()
                        && !ItemStack.isSameItemSameComponents(current, stack)) continue;
                int capacity = slot.getMaxStackSize(stack) - current.getCount();
                int amount = Math.min(Math.min(wanted, stack.getCount()), capacity);
                if (amount > 0) {
                    int clicks = ContainerClickPlan.exact(stack.getCount(), amount).size();
                    return new Move(source, destination, key, amount, clicks);
                }
            }
        }
        return null;
    }

    private record Move(int source, int destination, String key, int amount, int clicks) {
    }

    private TaskModuleResult finish(boolean success, String message) {
        if (success && !stop && opened && !contents.isEmpty()
                && (context.workspace().cacheContents(target).isPresent()
                        || context.workspace().containerScan().stream()
                                .flatMap(scan -> scan.containers().stream())
                                .anyMatch(cache -> cache.entrances().stream()
                                        .anyMatch(target.entrances()::contains)))) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            contents.stream().filter(stack -> !stack.isEmpty()).forEach(stack ->
                    counts.merge(MinecraftItemIdentity.key(stack), stack.getCount(),
                            Math::addExact));
            context.workspace().recordCacheContents(target, counts);
        }
        done = true;
        visit.cancelNavigation();
        if (minecraft.player != null && minecraft.player.containerMenu.getCarried().isEmpty()) {
            dev.stowling.v2.runtime.TaskTrace.event("CALL", "game.closeContainer",
                    "target=" + target.position() + "; success=" + success, 0, false);
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
        visit.cancelNavigation();
        if (safeToStop() && minecraft.player != null) {
            minecraft.player.closeContainer();
        }
    }

    static List<Integer> slots(AbstractContainerMenu menu, boolean player) {
        return MinecraftContainerInteractions.slots(menu, player);
    }

    private static int count(AbstractContainerMenu menu, boolean player, String key) {
        return slots(menu, player).stream().map(menu::getSlot).map(Slot::getItem)
                .filter(stack -> !stack.isEmpty() && MinecraftItemIdentity.key(stack).equals(key))
                .mapToInt(ItemStack::getCount).sum();
    }

}
