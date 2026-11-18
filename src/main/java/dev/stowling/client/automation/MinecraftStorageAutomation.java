package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerVisitPlan;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.domain.BlockPosition;
import dev.stowling.inventory.RecordedStorageCapacity;
import dev.stowling.inventory.WarehouseInventory;
import dev.stowling.v2.config.StorageSettingsStore;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.StorageAutomationSettings;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePackingPlanner;
import dev.stowling.v2.model.StoragePolicy;
import dev.stowling.v2.runtime.modules.StorageAutomationPort;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;

/** Shared packing and cleanup coordinator. One active task owns the client's interaction slot. */
public final class MinecraftStorageAutomation implements StorageAutomationPort {
    private final Minecraft minecraft;
    private final StorageSettingsStore settings;
    private final dev.stowling.automation.ContainerRegionPort containerRegions;
    private final Supplier<WarehouseInventory> warehouse;
    private final Map<UUID, MinecraftShulkerAction.Resource> resources = new LinkedHashMap<>();

    public MinecraftStorageAutomation(Minecraft minecraft, StorageSettingsStore settings) {
        this(minecraft, settings, (region, types, volume) ->
                dev.stowling.automation.ContainerScanResult.failure("区域扫描组件不可用"));
    }

    public MinecraftStorageAutomation(Minecraft minecraft, StorageSettingsStore settings,
            dev.stowling.automation.ContainerRegionPort containerRegions) {
        this(minecraft, settings, containerRegions, () -> null);
    }

    public MinecraftStorageAutomation(Minecraft minecraft, StorageSettingsStore settings,
            dev.stowling.automation.ContainerRegionPort containerRegions,
            Supplier<WarehouseInventory> warehouse) {
        this.minecraft = minecraft;
        this.settings = settings;
        this.containerRegions = containerRegions;
        this.warehouse = warehouse;
    }

    @Override
    public boolean cleanupEnabled() {
        return settings.resolved().cleanupEnabled();
    }

    @Override
    public boolean hasTemporaryResources(UUID owner) {
        return resources.containsKey(owner);
    }

    @Override
    public List<ContainerTarget> temporaryContainers() {
        return dev.stowling.automation.TemporaryContainerResolver.resolve(
                settings.resolved(), settings.temporaryRegions(), containerRegions);
    }

    @Override
    public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
            Map<String, Integer> items) {
        return new Packing(context, target, items);
    }

    @Override
    public Optional<TaskModuleExecutor> prepareDeposit(TaskExecutionContext context,
            ContainerTarget target, Map<String, Integer> items) {
        if (target.storagePolicy().mode() == StorageMode.NORMAL
                || !context.workspace().isClassificationStorage(target)
                || settings.resolved().emptyBoxContainers().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PrepareBox(context, target, items));
    }

    @Override
    public Comparator<ContainerTarget> storageOrder(Map<String, Integer> items) {
        if (minecraft.player == null || items.isEmpty()) {
            return StorageAutomationPort.super.storageOrder(items);
        }
        WarehouseInventory snapshot = warehouse.get();
        Map<BlockPosition, WarehouseInventory.Container> records = new LinkedHashMap<>();
        Map<String, Integer> stackSizes = new LinkedHashMap<>();
        for (ItemStack stack : inventoryStacks()) {
            stackSizes.put(MinecraftItemIdentity.key(stack), stack.getMaxStackSize());
        }
        if (snapshot != null) {
            for (WarehouseInventory.Container stock : snapshot.containers()) {
                if (stock.dimension().equals(snapshot.currentDimension())) {
                    stock.positions().forEach(position -> records.put(position, stock));
                }
            }
        }
        return Comparator.<ContainerTarget>comparingInt(target -> RecordedStorageCapacity.rank(
                        records.get(target.position()), target.storagePolicy(), items, stackSizes))
                .thenComparingDouble(this::storageDistance)
                .thenComparingDouble(target -> target.entrances().stream()
                        .mapToDouble(this::distance).min().orElseThrow());
    }

    private double storageDistance(ContainerTarget target) {
        AreaBounds area = target.standingArea();
        if (area == null) {
            return target.entrances().stream().mapToDouble(this::distance).min().orElseThrow();
        }
        BlockPos feet = minecraft.player.blockPosition();
        return distance(new BlockPosition(Math.clamp(feet.getX(), area.minX(), area.maxX()),
                Math.clamp(feet.getY(), area.minY(), area.maxY()),
                Math.clamp(feet.getZ(), area.minZ(), area.maxZ())));
    }

    @Override
    public TaskModuleExecutor cleanup(TaskExecutionContext context) {
        return new Cleanup(context);
    }

    @Override
    public TaskModuleExecutor scanCacheContents(TaskExecutionContext context,
            List<ContainerTarget> containers) {
        return new CacheInventoryScan(context, containers);
    }

    private abstract class Session implements TaskModuleExecutor {
        final TaskExecutionContext context;
        final StorageAutomationSettings configuration = settings.resolved();
        final Deque<Step> steps = new ArrayDeque<>();
        Step current;
        boolean stopping;
        TaskModuleResult terminal;

        Session(TaskExecutionContext context) {
            this.context = context;
        }

        abstract void initialize();

        String completionMessage() {
            return "储存操作完成";
        }

        @Override
        public TaskModuleResult start() {
            try {
                initialize();
                return tick();
            } catch (RuntimeException exception) {
                terminal = TaskModuleResult.failed(exception.getMessage());
                return terminal;
            }
        }

        @Override
        public TaskModuleResult tick() {
            if (terminal != null) {
                return terminal;
            }
            try {
                if (stopping && safeToStop()) {
                    return TaskModuleResult.succeeded("操作已确认，交给全局收尾");
                }
                if (current == null) {
                    current = steps.pollFirst();
                    if (current == null) {
                        terminal = TaskModuleResult.succeeded(completionMessage());
                        return terminal;
                    }
                }
                TaskModuleResult result = current.action.tick();
                if (result.state() == TaskModuleState.SUCCEEDED) {
                    Runnable after = current.after;
                    current = null;
                    if (!stopping) {
                        after.run();
                    }
                    return TaskModuleResult.waiting("正在确认储存流程");
                }
                if (result.state() == TaskModuleState.FAILED) {
                    if (current.failed != null && current.action.safeToStop()) {
                        Runnable failed = current.failed;
                        current = null;
                        failed.run();
                        return TaskModuleResult.waiting("正在尝试下一个可用暂存箱");
                    }
                    terminal = result;
                }
                return result;
            } catch (RuntimeException exception) {
                terminal = TaskModuleResult.failed("储存操作失败：" + exception.getMessage());
                return terminal;
            }
        }

        final void read(ContainerTarget target, Consumer<List<ItemStack>> after) {
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    target, Map.of(), false, true);
            steps.add(new Step(access, () -> after.accept(access.contents())));
        }

        final void transfer(ContainerTarget target, Map<String, Integer> items, boolean withdraw,
                Runnable after) {
            if (items.isEmpty()) {
                after.run();
                return;
            }
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    target, items, withdraw, false);
            steps.add(new Step(access, after));
        }

        final void withdrawEmptyBox(int index, Consumer<ItemStack> after) {
            if (index >= configuration.emptyBoxContainers().size()) {
                throw new IllegalStateException("空潜影盒供应不足，剩余物品等待收尾");
            }
            ContainerTarget supply = new ContainerTarget(
                    configuration.emptyBoxContainers().get(index), "container");
            read(supply, contents -> {
                ItemStack emptyBox = contents.stream()
                        .filter(MinecraftStorageAutomation::isEmptyBox)
                        .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
                if (emptyBox.isEmpty()) {
                    withdrawEmptyBox(index + 1, after);
                    return;
                }
                String key = MinecraftItemIdentity.key(emptyBox);
                int before = owned().getOrDefault(key, 0);
                MinecraftStorageAccess access = MinecraftStorageAccess.withdrawEmptyBox(
                        minecraft, context, supply, key);
                steps.add(new Step(access, () -> {
                    if (owned().getOrDefault(key, 0) != before + 1) {
                        throw new IllegalStateException("背包空间不足或空潜影盒取出未确认");
                    }
                    after.accept(emptyBox);
                }));
            });
        }

        final UUID owner() {
            return context.workspace().inventoryAccount().ownerId();
        }

        final void recover(Runnable after) {
            MinecraftShulkerAction.Resource resource = resources.get(owner());
            if (resource == null) {
                after.run();
                return;
            }
            BlockPos position = position(resource.position);
            if (!resource.placed) {
                if (minecraft.level.getBlockState(position).isAir()
                        && owned().getOrDefault(MinecraftItemIdentity.key(resource.original), 0)
                        > 0) {
                    resources.remove(owner());
                    after.run();
                    return;
                }
                if (resource.original.getItem() instanceof net.minecraft.world.item.BlockItem item
                        && minecraft.level.getBlockState(position).is(item.getBlock())) {
                    context.workspace().consumeDeposit("managed",
                            Map.of(MinecraftItemIdentity.key(resource.original), 1), Map.of());
                    resource.placed = true;
                } else {
                    throw new IllegalStateException("潜影盒放置状态未确认，保留收尾记录");
                }
            }
            Runnable breakBox = () -> steps.add(new Step(new MinecraftShulkerAction(minecraft,
                    context, resource, false), () -> {
                        resources.remove(owner());
                        after.run();
                    }));
            if (resource.broken) {
                breakBox.run();
            } else {
                read(new ContainerTarget(resource.position, "minecraft:shulker_box"), contents -> {
                    resource.expected = resource.original.copyWithCount(1);
                    resource.expected.set(DataComponents.CONTAINER,
                            ItemContainerContents.fromItems(contents));
                    breakBox.run();
                });
            }
        }

        final Map<String, Integer> owned() {
            return context.workspace().managedItems();
        }

        @Override
        public void requestStop() {
            stopping = true;
            if (current != null) {
                current.action.requestStop();
            }
        }

        @Override
        public boolean safeToStop() {
            return current == null || current.action.safeToStop();
        }

        @Override
        public void cancel() {
            if (current != null) {
                current.action.cancel();
            }
        }
    }

    private record Step(TaskModuleExecutor action, Runnable after, Runnable failed) {
        Step(TaskModuleExecutor action, Runnable after) {
            this(action, after, null);
        }
    }

    private final class CacheInventoryScan extends Session {
        private final ContainerVisitPlan visits;
        private final Map<BlockPosition, Map<String, Integer>> counts = new LinkedHashMap<>();

        CacheInventoryScan(TaskExecutionContext context, List<ContainerTarget> containers) {
            super(context);
            visits = new ContainerVisitPlan(containers);
        }

        @Override
        void initialize() {
            visitNext();
        }

        private void visitNext() {
            if (minecraft.player == null) {
                throw new IllegalStateException("缓存盘点中止：当前世界不可用");
            }
            BlockPos feet = minecraft.player.blockPosition();
            ContainerTarget next = visits.next(new BlockPosition(feet.getX(), feet.getY(),
                    feet.getZ())).orElse(null);
            if (next == null) {
                context.workspace().recordCacheInventory(counts);
                return;
            }
            read(next, contents -> {
                Map<String, Integer> items = new LinkedHashMap<>();
                for (ItemStack stack : contents) {
                    if (!stack.isEmpty()) {
                        items.merge(MinecraftItemIdentity.key(stack), stack.getCount(),
                                Math::addExact);
                    }
                }
                counts.put(next.position(), items);
                visitNext();
            });
        }

        @Override
        String completionMessage() {
            return "已完成缓存库存盘点，共读取 " + counts.size() + " 个容器";
        }
    }

    private final class PrepareBox extends Session {
        private final ContainerTarget target;
        private final Map<String, Integer> items;

        PrepareBox(TaskExecutionContext context, ContainerTarget target,
                Map<String, Integer> items) {
            super(context);
            this.target = target;
            this.items = items;
        }

        @Override
        void initialize() {
            if (configuration.cleanupEnabled() && ownedEmptyBox(owned()).isEmpty()
                    && needsBox()) {
                withdrawEmptyBox(0, box -> { });
            }
        }

        private boolean needsBox() {
            WarehouseInventory inventory = warehouse.get();
            WarehouseInventory.Container stock = inventory == null ? null
                    : inventory.containers().stream().filter(container -> container.slots() > 0
                            && container.dimension().equals(inventory.currentDimension())
                            && container.positions().contains(target.position()))
                    .findFirst().orElse(null);
            for (ItemStack stack : inventoryStacks()) {
                String key = MinecraftItemIdentity.key(stack);
                if (ItemIdentity.isBox(key) || !items.containsKey(key)) {
                    continue;
                }
                String category = ItemIdentity.category(key);
                int stackSize = stack.getMaxStackSize();
                int incoming = items.entrySet().stream()
                        .filter(entry -> !ItemIdentity.isBox(entry.getKey())
                                && category.equals(ItemIdentity.category(entry.getKey())))
                        .mapToInt(Map.Entry::getValue).sum();
                long sameLoose = 0;
                long otherSlots = 0;
                long matchingSlack = 0;
                if (stock != null) {
                    for (WarehouseInventory.Entry entry : stock.items()) {
                        if (ItemIdentity.isBox(entry.key())) {
                            continue;
                        }
                        if (category.equals(entry.itemId())) {
                            sameLoose += entry.loose();
                            long remainder = entry.loose() % entry.stackSize();
                            if (remainder > 0 && items.containsKey(entry.key())) {
                                matchingSlack += entry.stackSize() - remainder;
                            }
                        } else if (entry.loose() > 0) {
                            otherSlots += Math.ceilDiv(entry.loose(), entry.stackSize());
                        }
                    }
                }
                if (stock == null || stock.recordedAt() == null) {
                    if (StoragePackingPlanner.shouldBringBox(
                            target.storagePolicy(), incoming, 0, stackSize, 0)) {
                        return true;
                    }
                    continue;
                }
                StoragePolicy policy = target.storagePolicy();
                long limit = Math.max(0, StoragePackingPlanner.looseLimit(
                        policy, stock.slots(), stackSize) - otherSlots * stackSize);
                long space = StoragePackingPlanner.looseSpace(policy, stock.slots(), stackSize,
                        sameLoose, otherSlots, stock.occupiedSlots(), matchingSlack);
                if (StoragePackingPlanner.shouldBringBox(policy, incoming, space, stackSize,
                        Math.max(0, sameLoose - limit))) {
                    return true;
                }
            }
            return false;
        }
    }

    private final class Packing extends Session {
        private final ContainerTarget target;
        private final StoragePolicy policy;
        private final Deque<String> categories;
        private String category;
        private int stackSize;
        private int looseLimit;
        private List<ItemStack> targetContents = List.of();
        private ItemStack box;
        private ContainerTarget boxTarget;
        private boolean requestedBox;
        private boolean packedBox;
        private boolean skipLooseDeposit;
        private int targetExcessRemaining;
        private BlockPosition preferredSite;

        Packing(TaskExecutionContext context, ContainerTarget target, Map<String, Integer> items) {
            super(context);
            this.target = target;
            this.policy = target.storagePolicy();
            this.categories = new ArrayDeque<>(items.keySet().stream()
                    .map(ItemIdentity::category).distinct().toList());
        }

        @Override
        void initialize() {
            if (!configuration.cleanupEnabled() || configuration.emptyBoxContainers().isEmpty()) {
                throw new IllegalStateException("请先配置全局暂存箱、空盒供应并开启统一收尾");
            }
            if (ItemIdentity.isBox(target.type())) {
                throw new IllegalStateException("潜影盒储存模式需要普通箱子或木桶作为目标");
            }
            if (hasTemporaryResources(owner())) {
                throw new IllegalStateException("上次潜影盒尚未回收，请先执行全局收尾");
            }
            nextCategory();
        }

        private void nextCategory() {
            category = categories.pollFirst();
            if (category != null) {
                packedBox = false;
                skipLooseDeposit = false;
                inspectTarget();
            } else if (context.workspace().isClassificationStorage(target)) {
                stashUnusedSupplyBox(0);
            }
        }

        private void inspectTarget() {
            read(target, contents -> {
                targetContents = contents;
                ItemStack representative = inventoryStacks().stream()
                        .filter(stack -> MinecraftItemIdentity.base(stack).equals(category))
                        .findFirst().orElseGet(() -> contents.stream()
                                .filter(stack -> !stack.isEmpty()
                                        && MinecraftItemIdentity.base(stack).equals(category))
                                .findFirst().orElse(ItemStack.EMPTY));
                stackSize = representative.isEmpty() ? 64 : representative.getMaxStackSize();
                looseLimit = StoragePackingPlanner.looseLimit(policy, contents.size(), stackSize);
                if (policy.mode() == StorageMode.MIXED) {
                    long otherSlots = contents.stream().filter(stack -> !stack.isEmpty()
                            && !MinecraftItemIdentity.isBox(stack)
                            && !MinecraftItemIdentity.base(stack).equals(category)).count();
                    looseLimit = Math.max(0, looseLimit - Math.toIntExact(otherSlots) * stackSize);
                }
                List<ItemStack> boxes = inventoryStacks().stream().filter(stack ->
                        MinecraftItemIdentity.isBox(stack)
                                && category.equals(MinecraftItemIdentity.boxCategory(stack))
                                && owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                        .toList();
                ItemStack full = boxes.stream().filter(MinecraftItemIdentity::full)
                        .findFirst().orElse(ItemStack.EMPTY);
                if (!full.isEmpty()) {
                    String key = MinecraftItemIdentity.key(full);
                    transfer(target, Map.of(key, 1), false, () -> {
                        if (owned().getOrDefault(key, 0) >= boxes.stream()
                                .filter(stack -> MinecraftItemIdentity.key(stack).equals(key))
                                .mapToInt(ItemStack::getCount).sum()) {
                            throw new IllegalStateException("目标容器已满，满盒将由全局收尾暂存");
                        }
                        inspectTarget();
                    });
                    return;
                }
                int loose = rawCount(contents);
                Map<String, Integer> carried = rawOwned();
                int amount = Math.min(sum(carried), Math.max(0, looseLimit - loose));
                int physicalSpace = contents.stream().mapToInt(stack -> stack.isEmpty()
                        ? stackSize : carried.containsKey(MinecraftItemIdentity.key(stack))
                                ? stack.getMaxStackSize() - stack.getCount() : 0).sum();
                boolean fullBoxFirst = !policy.allowPartialBoxes()
                        && StoragePackingPlanner.shouldBringBox(policy, sum(carried),
                                Math.min(Math.max(0, looseLimit - loose), physicalSpace),
                                stackSize, Math.max(0, loose - looseLimit));
                if (amount > 0 && !skipLooseDeposit && !fullBoxFirst) {
                    int before = sum(carried);
                    transfer(target, take(carried, amount), false, () -> {
                        if (sum(rawOwned()) == before) {
                            skipLooseDeposit = true;
                            chooseBox();
                        } else {
                            inspectTarget();
                        }
                    });
                } else if (carried.isEmpty() && loose <= looseLimit && boxes.isEmpty()) {
                    nextCategory();
                } else {
                    chooseBox();
                }
            });
        }

        private void chooseBox() {
            box = inventoryStacks().stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                    && category.equals(MinecraftItemIdentity.boxCategory(stack))
                    && !MinecraftItemIdentity.full(stack)
                    && owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                    .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
            requestedBox = !box.isEmpty();
            ItemStack supplied = ownedEmptyBox(owned());
            targetExcessRemaining = Math.max(0, rawCount(targetContents) - looseLimit);
            int available = sum(rawOwned()) + targetExcessRemaining;
            if (!requestedBox && !supplied.isEmpty()
                    && targetContents.stream().anyMatch(ItemStack::isEmpty)
                    && StoragePackingPlanner.canPack(policy.allowPartialBoxes(),
                            available, 0, stackSize)) {
                box = supplied;
                requestedBox = true;
            }
            if (box.isEmpty()) {
                box = targetContents.stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                        && category.equals(MinecraftItemIdentity.boxCategory(stack))
                        && !MinecraftItemIdentity.full(stack))
                        .max(Comparator.comparingInt(MinecraftStorageAutomation::insideCount))
                        .map(ItemStack::copy).orElse(ItemStack.EMPTY);
            }
            if (box.isEmpty() && !supplied.isEmpty()) {
                box = supplied;
                requestedBox = true;
            }
            int inside = box.isEmpty() ? 0 : insideCount(box);
            if (!StoragePackingPlanner.canPack(policy.allowPartialBoxes(), available, inside, stackSize)) {
                if (requestedBox && policy.allowPartialBoxes()) {
                    storeBox();
                } else {
                    defer();
                }
                return;
            }
            if (box.isEmpty() && packedBox && context.workspace().isClassificationStorage(target)) {
                defer();
                return;
            }
            preferredSite = chooseSite(context, target, policy.packingArea(), configuration);
            if (!box.isEmpty()) {
                if (requestedBox) {
                    placeBox();
                } else {
                    String key = MinecraftItemIdentity.key(box);
                    transfer(target, Map.of(key, 1), true, () -> {
                        requireOwned(key);
                        placeBox();
                    });
                }
            } else {
                withdrawEmptyBox(0, suppliedBox -> {
                    box = suppliedBox;
                    placeBox();
                });
            }
        }

        private void requireOwned(String key) {
            if (owned().getOrDefault(key, 0) == 0) {
                throw new IllegalStateException("背包空间不足或指定潜影盒取出未确认");
            }
        }

        private void placeBox() {
            BlockPosition site = preferredSite;
            MinecraftShulkerAction.Resource resource = new MinecraftShulkerAction.Resource(site, box);
            resources.put(owner(), resource);
            boxTarget = new ContainerTarget(site, "minecraft:shulker_box");
            steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, true),
                    this::fillBox));
        }

        private void fillBox() {
            read(boxTarget, contents -> {
                int inside = rawCount(contents);
                int remaining = StoragePackingPlanner.BOX_SLOTS * stackSize - inside;
                Map<String, Integer> incoming = take(rawOwned(), remaining);
                if (!incoming.isEmpty()) {
                    int before = sum(rawOwned());
                    transfer(boxTarget, incoming, false, () -> {
                        if (sum(rawOwned()) == before) {
                            finishBox();
                        } else {
                            fillBox();
                        }
                    });
                } else if (remaining <= 0 || targetExcessRemaining <= 0) {
                    finishBox();
                } else {
                    loadTargetExcess(remaining);
                }
            });
        }

        private void loadTargetExcess(int wanted) {
            read(target, contents -> {
                Map<String, Integer> raw = rawItems(contents);
                int amount = Math.min(targetExcessRemaining, Math.max(0, sum(raw) - looseLimit));
                if (amount == 0) {
                    finishBox();
                } else {
                    int before = sum(rawOwned());
                    transfer(target, take(raw, Math.min(amount, wanted)), true, () -> {
                        int withdrawn = sum(rawOwned()) - before;
                        if (withdrawn <= 0) {
                            finishBox();
                        } else {
                            targetExcessRemaining -= withdrawn;
                            fillBox();
                        }
                    });
                }
            });
        }

        private void finishBox() {
            MinecraftShulkerAction.Resource resource = resources.get(owner());
            recover(() -> {
                box = resource.expected;
                requireOwned(MinecraftItemIdentity.key(box));
                if (!policy.allowPartialBoxes() && !MinecraftItemIdentity.full(box)) {
                    throw new IllegalStateException("物品不足或数据不同，未满盒交给全局收尾暂存");
                }
                storeBox();
            });
        }

        private void storeBox() {
            String key = MinecraftItemIdentity.key(box);
            int before = owned().getOrDefault(key, 0);
            transfer(target, Map.of(key, 1), false, () -> {
                if (owned().getOrDefault(key, 0) == before) {
                    throw new IllegalStateException("目标箱无法接收潜影盒，交给全局收尾暂存");
                }
                packedBox = true;
                skipLooseDeposit = false;
                inspectTarget();
            });
        }

        private void defer() {
            if (!context.workspace().isClassificationStorage(target)) {
                context.workspace().markTargetUnavailable(target);
                context.workspace().setCacheHasMore(false);
            }
            nextCategory();
        }

        private void stashUnusedSupplyBox(int index) {
            ItemStack unused = ownedEmptyBox(owned());
            if (unused.isEmpty()) {
                return;
            }
            List<ContainerTarget> staging = temporaryContainers();
            if (index >= staging.size()) {
                throw new IllegalStateException("暂存箱无法接收未使用的空潜影盒");
            }
            String key = MinecraftItemIdentity.key(unused);
            int before = owned().getOrDefault(key, 0);
            transfer(staging.get(index), Map.of(key, 1), false, () -> {
                if (owned().getOrDefault(key, 0) == before) {
                    stashUnusedSupplyBox(index + 1);
                }
            });
        }

        private int rawCount(List<ItemStack> contents) {
            return sum(rawItems(contents));
        }

        private Map<String, Integer> rawItems(List<ItemStack> contents) {
            Map<String, Integer> items = new LinkedHashMap<>();
            contents.stream().filter(stack -> !stack.isEmpty()
                    && !MinecraftItemIdentity.isBox(stack)
                    && MinecraftItemIdentity.base(stack).equals(category)).forEach(stack ->
                            items.merge(MinecraftItemIdentity.key(stack), stack.getCount(),
                                    Math::addExact));
            return items;
        }

        private Map<String, Integer> rawOwned() {
            Map<String, Integer> result = new LinkedHashMap<>();
            owned().forEach((key, count) -> {
                if (!ItemIdentity.isBox(key) && ItemIdentity.category(key).equals(category)) {
                    result.put(key, count);
                }
            });
            return result;
        }
    }

    private final class Cleanup extends Session {
        private List<ContainerTarget> targets;

        Cleanup(TaskExecutionContext context) {
            super(context);
        }

        @Override
        void initialize() {
            targets = temporaryContainers().stream()
                    .sorted(Comparator.comparingDouble(target -> distance(target.position())))
                    .toList();
            if (targets.isEmpty()) {
                throw new IllegalStateException("全局收尾缺少暂存箱，请配置后重试");
            }
            recover(() -> depositAll(0));
        }

        private void depositAll(int index) {
            context.inventory().observe();
            context.inventory().claimUnexpected(context.workspace().inventoryAccount(), Set.of());
            if (owned().isEmpty()) {
                return;
            }
            if (index >= targets.size()) {
                throw new IllegalStateException("暂存箱已满或无法接收剩余物品，收尾受阻");
            }
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    targets.get(index), owned(), false, false);
            steps.add(new Step(access, () -> depositAll(index + 1),
                    () -> depositAll(index + 1)));
        }
    }

    private BlockPosition chooseSite(TaskExecutionContext context, ContainerTarget target,
            AreaBounds allowed, StorageAutomationSettings configuration) {
        List<BlockPosition> candidates = new ArrayList<>();
        BlockPos player = minecraft.player.blockPosition();
        int minX = allowed == null ? player.getX() - 5
                : Math.min(allowed.firstX(), allowed.secondX());
        int maxX = allowed == null ? player.getX() + 5
                : Math.max(allowed.firstX(), allowed.secondX());
        int minY = allowed == null ? player.getY() - 1
                : Math.min(allowed.firstY(), allowed.secondY());
        int maxY = allowed == null ? player.getY() + 1
                : Math.max(allowed.firstY(), allowed.secondY());
        int minZ = allowed == null ? player.getZ() - 5
                : Math.min(allowed.firstZ(), allowed.secondZ());
        int maxZ = allowed == null ? player.getZ() + 5
                : Math.max(allowed.firstZ(), allowed.secondZ());
        if ((long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1) > 4096) {
            throw new IllegalStateException("单片打包区域最多包含 4096 个方块位置");
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPosition candidate = new BlockPosition(x, y, z);
                    if (!reserved(context, candidate)
                            && MinecraftShulkerAction.validSite(minecraft, position(candidate))) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        if (candidates.isEmpty() && configuration.fallbackPackingPosition() != null) {
            BlockPosition fallback = configuration.fallbackPackingPosition();
            if (!reserved(context, fallback)
                    && MinecraftShulkerAction.validSite(minecraft, position(fallback))) {
                return fallback;
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(candidate ->
                distance(candidate) + squared(candidate, target.position())))
                .orElseThrow(() -> new IllegalStateException("附近没有可用打包位置，请指定单片打包区域"));
    }

    private boolean reserved(TaskExecutionContext context, BlockPosition position) {
        return resources.values().stream().anyMatch(resource -> resource.position.equals(position))
                || context.categoryZones().values().stream().flatMap(zone -> zone.bindings().stream())
                .anyMatch(binding -> binding.markerArea().contains(position)
                        || binding.storageArea().contains(position)
                        || binding.standingArea() != null && binding.standingArea().contains(position));
    }

    private double distance(BlockPosition position) {
        BlockPos player = minecraft.player.blockPosition();
        return squared(position, new BlockPosition(player.getX(), player.getY(), player.getZ()));
    }

    private static double squared(BlockPosition first, BlockPosition second) {
        double x = (double) first.x() - second.x();
        double y = (double) first.y() - second.y();
        double z = (double) first.z() - second.z();
        return x * x + y * y + z * z;
    }

    private List<ItemStack> inventoryStacks() {
        return minecraft.player.getInventory().getNonEquipmentItems().stream()
                .filter(stack -> !stack.isEmpty()).map(ItemStack::copy).toList();
    }

    private static boolean isEmptyBox(ItemStack stack) {
        return MinecraftItemIdentity.isBox(stack)
                && MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream().findAny()
                        .isEmpty();
    }

    private ItemStack ownedEmptyBox(Map<String, Integer> owned) {
        return inventoryStacks().stream().filter(MinecraftStorageAutomation::isEmptyBox)
                .filter(stack -> owned.getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                .findFirst().orElse(ItemStack.EMPTY);
    }

    private static BlockPos position(BlockPosition position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static int insideCount(ItemStack box) {
        return MinecraftItemIdentity.contents(box).nonEmptyItemCopyStream()
                .mapToInt(ItemStack::getCount).sum();
    }

    private static int sum(Map<String, Integer> items) {
        return items.values().stream().mapToInt(Integer::intValue).sum();
    }

    private static Map<String, Integer> take(Map<String, Integer> items, int limit) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (var entry : items.entrySet()) {
            int count = Math.min(entry.getValue(), Math.max(0, limit));
            if (count > 0) {
                result.put(entry.getKey(), count);
                limit -= count;
            }
        }
        return result;
    }
}
