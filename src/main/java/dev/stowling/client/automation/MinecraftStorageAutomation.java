package dev.stowling.client.automation;

import dev.stowling.automation.ContainerRecoveryDrop;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerVisitPlan;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.EmptyBoxSupplyPlan;
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
import dev.stowling.v2.runtime.modules.ContainerBatchWithdrawal;
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
    public boolean isRecoveryDrop(DroppedItem item) {
        return resources.values().stream().anyMatch(resource -> resource.placed
                && ContainerRecoveryDrop.matches(item, resource.position,
                        MinecraftItemIdentity.key(resource.expected)));
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
                || emptyBoxSources(context).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new PrepareBox(context, target, items));
    }

    @Override
    public Optional<TaskModuleExecutor> unpackMixedBox(TaskExecutionContext context,
            ContainerTarget cache) {
        return context.workspace().hasMixedCacheStock(cache)
                ? Optional.of(new UnpackMixedBox(context, cache)) : Optional.empty();
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
        return cleanup(context, false);
    }

    @Override
    public TaskModuleExecutor cleanup(TaskExecutionContext context, boolean overflowEnabled) {
        return new Cleanup(context, overflowEnabled);
    }

    @Override
    public TaskModuleExecutor overflow(TaskExecutionContext context,
            dev.stowling.v2.model.RegionDefinition cacheRegion) {
        return new Overflow(context, cacheRegion);
    }

    private List<ContainerTarget> emptyBoxSources(TaskExecutionContext context) {
        List<ContainerTarget> caches = new ArrayList<>();
        context.workspace().containerScan().ifPresent(scan -> caches.addAll(scan.containers()));
        caches.addAll(context.workspace().cacheContainers());
        return EmptyBoxSupplyPlan.sources(caches,
                context.workspace().selectedContainer().orElse(null),
                settings.resolved().emptyBoxContainers());
    }

    private List<ContainerTarget> overflowContainers() {
        return dev.stowling.automation.TemporaryContainerResolver.resolve(settings.overflowPositions(),
                settings.overflowRegions(), containerRegions,
                Set.copyOf(settings.resolved().emptyBoxContainers()), "溢出");
    }

    @Override
    public TaskModuleExecutor scanCacheContents(TaskExecutionContext context,
            List<ContainerTarget> containers) {
        return new CacheInventoryScan(context, containers);
    }

    @Override
    public List<ContainerTarget> recoveryContainers(TaskExecutionContext context) {
        return taskCleanupContainers(context, settings.resolved());
    }

    private List<ContainerTarget> taskCleanupContainers(TaskExecutionContext context,
            StorageAutomationSettings configuration) {
        var ref = context.workspace().cleanupPolicy().destinationRef();
        List<ContainerTarget> destinations;
        if (ref == null) {
            destinations = temporaryContainers();
        } else {
            var destination = dev.stowling.v2.model.ResourceReference.parse(ref);
            List<BlockPosition> points = List.of();
            List<dev.stowling.v2.model.RegionDefinition> regions = List.of();
            if (destination.type() == dev.stowling.v2.model.ResourceType.COORDINATE) {
                var point = context.coordinates().get(destination.uuid());
                if (point == null) throw new IllegalStateException("中断存放坐标不存在");
                points = List.of(new BlockPosition(point.x(), point.y(), point.z()));
            } else {
                var region = context.regions().get(destination.uuid());
                if (region == null) throw new IllegalStateException("中断存放区域不存在");
                regions = List.of(region);
            }
            destinations = dev.stowling.automation.TemporaryContainerResolver.resolve(points,
                    regions, containerRegions, Set.copyOf(configuration.emptyBoxContainers()),
                    "任务中断存放");
        }
        return destinations;
    }

    private List<ContainerTarget> partialBoxContainers(TaskExecutionContext context,
            StoragePolicy policy) {
        String encoded = policy.partialBoxDestinationRef();
        if (encoded == null) return List.of();
        var ref = dev.stowling.v2.model.ResourceReference.parse(encoded);
        List<BlockPosition> points = List.of();
        List<dev.stowling.v2.model.RegionDefinition> regions = List.of();
        if (ref.type() == dev.stowling.v2.model.ResourceType.COORDINATE) {
            var point = context.coordinates().get(ref.uuid());
            if (point == null) throw new IllegalStateException("未满盒存放坐标不存在");
            points = List.of(new BlockPosition(point.x(), point.y(), point.z()));
        } else {
            var region = context.regions().get(ref.uuid());
            if (region == null) throw new IllegalStateException("未满盒存放区域不存在");
            regions = List.of(region);
        }
        return dev.stowling.automation.TemporaryContainerResolver.resolve(points, regions,
                containerRegions, Set.copyOf(settings.resolved().emptyBoxContainers()),
                "未满盒存放");
    }

    private static boolean sameContainer(ContainerTarget first, ContainerTarget second) {
        return first.entrances().stream().anyMatch(second.entrances()::contains);
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

        final boolean cleanupAvailable() {
            return configuration.cleanupEnabled()
                    || context.workspace().cleanupPolicy().destinationRef() != null;
        }

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
            read(target, after, null);
        }

        final void read(ContainerTarget target, Consumer<List<ItemStack>> after, Runnable failed) {
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    target, Map.of(), false, true);
            steps.add(new Step(access, () -> after.accept(access.contents()), failed));
        }

        final void transfer(ContainerTarget target, Map<String, Integer> items, boolean withdraw,
                Runnable after) {
            transfer(target, items, withdraw, contents -> after.run());
        }

        final void transfer(ContainerTarget target, Map<String, Integer> items, boolean withdraw,
                Consumer<List<ItemStack>> after) {
            if (items.isEmpty()) {
                after.accept(List.of());
                return;
            }
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    target, items, withdraw, false);
            steps.add(new Step(access, () -> after.accept(access.contents())));
        }

        final void withdrawEmptyBox(int index, Consumer<ItemStack> after) {
            List<ContainerTarget> supplies = emptyBoxSources(context);
            if (index >= supplies.size()) {
                throw new IllegalStateException("空潜影盒供应不足，剩余物品等待收尾");
            }
            ContainerTarget supply = supplies.get(index);
            if (context.workspace().cacheContents(supply)
                    .map(items -> items.keySet().stream().noneMatch(ItemIdentity::isEmptyBox))
                    .orElse(false)) {
                withdrawEmptyBox(index + 1, after);
                return;
            }
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
                Runnable confirmed = () -> {
                    if (owned().getOrDefault(key, 0) == before + 1) {
                        after.accept(emptyBox);
                    } else {
                        withdrawEmptyBox(index + 1, after);
                    }
                };
                steps.add(new Step(access, confirmed, confirmed));
            }, () -> withdrawEmptyBox(index + 1, after));
        }

        final void findPartialBox(List<ContainerTarget> sources, int index, String category,
                Set<String> skipped,
                java.util.function.BiConsumer<ContainerTarget, ItemStack> after) {
            if (index >= sources.size()) {
                after.accept(null, ItemStack.EMPTY);
                return;
            }
            ContainerTarget source = sources.get(index);
            read(source, contents -> {
                ItemStack found = contents.stream().filter(stack ->
                        MinecraftItemIdentity.isBox(stack)
                                && category.equals(MinecraftItemIdentity.boxCategory(stack))
                                && !MinecraftItemIdentity.full(stack)
                                && !skipped.contains(MinecraftItemIdentity.key(stack)))
                        .max(Comparator.comparingInt(MinecraftStorageAutomation::insideCount))
                        .map(ItemStack::copy).orElse(ItemStack.EMPTY);
                if (found.isEmpty()) {
                    findPartialBox(sources, index + 1, category, skipped, after);
                } else {
                    after.accept(source, found);
                }
            }, () -> findPartialBox(sources, index + 1, category, skipped, after));
        }

        final void storePackedBox(ContainerTarget target, ItemStack box,
                Consumer<List<ItemStack>> after) {
            List<ContainerTarget> destinations = MinecraftItemIdentity.full(box) || isEmptyBox(box)
                    || target.storagePolicy().partialBoxDestinationRef() == null
                    ? List.of(target) : partialBoxContainers(context, target.storagePolicy());
            storePackedBox(target, box, destinations, 0, after);
        }

        private void storePackedBox(ContainerTarget target, ItemStack box,
                List<ContainerTarget> destinations, int index, Consumer<List<ItemStack>> after) {
            if (index >= destinations.size()) {
                throw new IllegalStateException("所选储存位置无法接收潜影盒，等待全局收尾");
            }
            ContainerTarget destination = destinations.get(index);
            String key = MinecraftItemIdentity.key(box);
            int before = owned().getOrDefault(key, 0);
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    destination, Map.of(key, 1), false, false);
            Runnable confirmed = () -> {
                int remaining = owned().getOrDefault(key, 0);
                if (remaining == before - 1) {
                    if (sameContainer(target, destination)) after.accept(access.contents());
                    else if (target.storagePolicy().mode() == StorageMode.BOX_ONLY) {
                        after.accept(List.of());
                    } else {
                        read(target, after);
                    }
                } else if (remaining == before) {
                    storePackedBox(target, box, destinations, index + 1, after);
                } else {
                    throw new IllegalStateException("潜影盒存入数量未确认，等待全局收尾");
                }
            };
            steps.add(new Step(access, confirmed, confirmed));
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
            if (resource.receipt.confirmed()) {
                resources.remove(owner());
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
                counts.forEach((position, items) -> context.workspace().recordCacheContents(
                        new ContainerTarget(position, "container"), items));
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

    private final class UnpackMixedBox extends Session {
        private final ContainerTarget cache;
        private ItemStack box;
        private ContainerBatchWithdrawal drain;
        private final List<ContainerTarget> returnTargets = new ArrayList<>();

        UnpackMixedBox(TaskExecutionContext context, ContainerTarget cache) {
            super(context);
            this.cache = cache;
        }

        @Override
        void initialize() {
            read(cache, contents -> {
                recordContents(contents);
                box = contents.stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                        && ItemIdentity.MIXED_BOX_CATEGORY.equals(
                                MinecraftItemIdentity.boxCategory(stack)))
                        .filter(stack -> !eligibleItems(stack).isEmpty())
                        .min(Comparator.comparingInt(this::layoutPriority))
                        .map(ItemStack::copy).orElse(ItemStack.EMPTY);
                if (box.isEmpty()) {
                    return;
                }
                if (!cleanupAvailable()) {
                    throw new IllegalStateException("拆分混杂盒前请配置任务中断存放位置");
                }
                if (hasTemporaryResources(owner())) {
                    throw new IllegalStateException("上次潜影盒尚未回收，请先执行全局收尾");
                }
                BlockPosition site = chooseSite(context, cache, null, configuration);
                String key = MinecraftItemIdentity.key(box);
                Set<String> innerKeys = eligibleItems(box);
                int before = owned().getOrDefault(key, 0);
                transfer(cache, Map.of(key, 1), true, () -> {
                    if (owned().getOrDefault(key, 0) != before + 1) {
                        throw new IllegalStateException("混杂潜影盒取出未确认，已停止拆盒");
                    }
                    var resource = new MinecraftShulkerAction.Resource(site, box);
                    resources.put(owner(), resource);
                    drain = new ContainerBatchWithdrawal(context,
                            new ContainerTarget(site, "minecraft:shulker_box"), innerKeys);
                    steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource,
                            true), () -> steps.add(new Step(drain, this::recoverBox))));
                });
            });
        }

        private Set<String> eligibleItems(ItemStack candidate) {
            Set<String> contents = MinecraftItemIdentity.contents(candidate).nonEmptyItemCopyStream()
                    .map(MinecraftItemIdentity::key).collect(java.util.stream.Collectors.toSet());
            return context.workspace().mixedBoxWithdrawalItems(cache, contents);
        }

        private int layoutPriority(ItemStack candidate) {
            List<String> order = context.workspace().classificationItemOrder(
                    context.workspace().remainingCacheCategories(cache));
            Set<String> contents = eligibleItems(candidate);
            for (int index = 0; index < order.size(); index++) {
                Set<String> category = Set.of(order.get(index));
                if (contents.stream().anyMatch(key -> ItemIdentity.matches(key, category))) {
                    return index;
                }
            }
            return Integer.MAX_VALUE;
        }

        private void recoverBox() {
            var resource = resources.get(owner());
            recover(() -> {
                box = resource.expected;
                if (isEmptyBox(box)) {
                    configuration.emptyBoxContainers().forEach(position ->
                            returnTargets.add(new ContainerTarget(position, "container")));
                    returnTargets.addAll(temporaryContainers());
                }
                returnTargets.add(cache);
                returnBox(0);
            });
        }

        private void returnBox(int index) {
            if (index >= returnTargets.size()) {
                throw new IllegalStateException("拆盒后的潜影盒无法归还，交给全局收尾");
            }
            String key = MinecraftItemIdentity.key(box);
            int before = owned().getOrDefault(key, 0);
            ContainerTarget destination = returnTargets.get(index);
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    destination, Map.of(key, 1), false, false);
            Runnable after = () -> {
                if (owned().getOrDefault(key, 0) == before - 1) {
                    read(cache, contents -> {
                        recordContents(contents);
                        if (drain.withdrawn().isEmpty()) {
                            throw new IllegalStateException("背包空间不足，混杂盒已完整放回缓存");
                        }
                    });
                } else {
                    returnBox(index + 1);
                }
            };
            steps.add(new Step(access, after, after));
        }

        private void recordContents(List<ItemStack> contents) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            contents.stream().filter(stack -> !stack.isEmpty()).forEach(stack ->
                    counts.merge(MinecraftItemIdentity.key(stack), stack.getCount(), Math::addExact));
            context.workspace().recordCacheContents(cache, counts);
        }

        @Override
        String completionMessage() {
            return "混杂盒已分批拆取，潜影盒已回收归还";
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
            // Pure-box packing first reuses carried or stored partial boxes at the current site.
            if (target.storagePolicy().mode() == StorageMode.BOX_ONLY) return;
            if (cleanupAvailable() && ownedEmptyBox(owned()).isEmpty()
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
            Map<String, Integer> stackSizes = new LinkedHashMap<>();
            for (ItemStack stack : inventoryStacks()) {
                stackSizes.put(MinecraftItemIdentity.key(stack), stack.getMaxStackSize());
            }
            return RecordedStorageCapacity.shouldReserveBox(stock, target.storagePolicy(),
                    items, stackSizes, recordedPartialBoxes(inventory));
        }

        private List<WarehouseInventory.Box> recordedPartialBoxes(WarehouseInventory inventory) {
            String encoded = target.storagePolicy().partialBoxDestinationRef();
            if (inventory == null || encoded == null) return List.of();
            var ref = dev.stowling.v2.model.ResourceReference.parse(encoded);
            java.util.function.Predicate<BlockPosition> contains;
            if (ref.type() == dev.stowling.v2.model.ResourceType.COORDINATE) {
                var point = context.coordinates().get(ref.uuid());
                if (point == null) return List.of();
                var position = new BlockPosition(point.x(), point.y(), point.z());
                contains = position::equals;
            } else {
                var region = context.regions().get(ref.uuid());
                if (region == null) return List.of();
                var bounds = new AreaBounds(region.firstX(), region.firstY(), region.firstZ(),
                        region.secondX(), region.secondY(), region.secondZ());
                contains = bounds::contains;
            }
            return inventory.containers().stream()
                    .filter(container -> container.recordedAt() != null
                            && container.dimension().equals(inventory.currentDimension())
                            && container.positions().stream().anyMatch(contains)
                            && container.positions().stream()
                                    .noneMatch(target.entrances()::contains))
                    .flatMap(container -> container.boxes().stream()).toList();
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
        private boolean checkedPartial;
        private ContainerTarget boxSource;
        private final Set<String> packedBoxes = new java.util.HashSet<>();
        private List<ContainerTarget> partialSources = List.of();
        private boolean skipLooseDeposit;
        private int targetExcessRemaining;
        private BlockPosition preferredSite;

        Packing(TaskExecutionContext context, ContainerTarget target, Map<String, Integer> items) {
            super(context);
            this.target = target;
            this.policy = target.storagePolicy();
            Set<String> orderedItems = new java.util.LinkedHashSet<>(
                    context.workspace().classificationItemOrder(items.keySet()));
            orderedItems.addAll(items.keySet());
            this.categories = new ArrayDeque<>(orderedItems.stream()
                    .map(ItemIdentity::category).distinct().toList());
        }

        @Override
        void initialize() {
            if (!cleanupAvailable() || emptyBoxSources(context).isEmpty()) {
                throw new IllegalStateException("请配置任务中断存放位置，并在缓存或公共供应箱准备空盒");
            }
            if (ItemIdentity.isBox(target.type())) {
                throw new IllegalStateException("潜影盒储存模式需要普通箱子或木桶作为目标");
            }
            if (hasTemporaryResources(owner())) {
                throw new IllegalStateException("上次潜影盒尚未回收，请先执行全局收尾");
            }
            List<ContainerTarget> configuredPartials = partialBoxContainers(context, policy);
            if (policy.mode() == StorageMode.BOX_ONLY) {
                partialSources = policy.partialBoxDestinationRef() == null
                        ? List.of(target) : configuredPartials;
            } else {
                partialSources = configuredPartials.stream()
                        .filter(source -> !sameContainer(source, target)).toList();
            }
            nextCategory();
        }

        private void nextCategory() {
            category = categories.pollFirst();
            if (category != null) {
                skipLooseDeposit = false;
                checkedPartial = false;
                packedBoxes.clear();
                if (policy.mode() == StorageMode.BOX_ONLY) packBoxesOnly();
                else inspectTarget();
            } else if (context.workspace().isClassificationStorage(target)
                    && EmptyBoxSupplyPlan.canReturnUnusedBoxes(owned(), inventoryStacks().stream()
                            .filter(MinecraftStorageAutomation::isEmptyBox)
                            .map(MinecraftItemIdentity::key)
                            .collect(java.util.stream.Collectors.toSet()))) {
                stashUnusedSupplyBox(0);
            }
        }

        private void inspectTarget() {
            read(target, this::inspectContents);
        }

        private void packBoxesOnly() {
            targetContents = List.of();
            looseLimit = 0;
            List<ItemStack> inventory = inventoryStacks();
            List<ItemStack> carriedBoxes = inventory.stream().filter(stack ->
                    MinecraftItemIdentity.isBox(stack)
                            && category.equals(MinecraftItemIdentity.boxCategory(stack))
                            && owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                    .toList();
            ItemStack full = carriedBoxes.stream().filter(MinecraftItemIdentity::full)
                    .findFirst().orElse(ItemStack.EMPTY);
            if (!full.isEmpty()) {
                box = full;
                storeBox();
                return;
            }
            ItemStack representative = inventory.stream().filter(stack ->
                    !MinecraftItemIdentity.isBox(stack)
                            && category.equals(MinecraftItemIdentity.base(stack)))
                    .findFirst().orElseGet(() -> carriedBoxes.stream()
                            .flatMap(stack -> MinecraftItemIdentity.contents(stack)
                                    .nonEmptyItemCopyStream())
                            .findFirst().orElse(ItemStack.EMPTY));
            stackSize = representative.isEmpty() ? 64 : representative.getMaxStackSize();
            if (rawOwned().isEmpty() && carriedBoxes.isEmpty()) {
                finishCategory();
            } else {
                chooseBox();
            }
        }

        private void inspectContents(List<ItemStack> contents) {
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
                transfer(target, Map.of(key, 1), false, updated -> {
                    if (owned().getOrDefault(key, 0) >= boxes.stream()
                            .filter(stack -> MinecraftItemIdentity.key(stack).equals(key))
                            .mapToInt(ItemStack::getCount).sum()) {
                        throw new IllegalStateException("目标容器已满，满盒将由全局收尾暂存");
                    }
                    inspectContents(updated);
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
                            stackSize, Math.max(0, loose - looseLimit), 0, loose);
            if (amount > 0 && !skipLooseDeposit && !fullBoxFirst) {
                int before = sum(carried);
                transfer(target, take(carried, amount), false, updated -> {
                    if (sum(rawOwned()) == before) {
                        skipLooseDeposit = true;
                        inspectContents(updated);
                    } else {
                        inspectContents(updated);
                    }
                });
            } else if (carried.isEmpty() && loose <= looseLimit && boxes.isEmpty()) {
                finishCategory();
            } else {
                chooseBox();
            }
        }

        private void chooseBox() {
            box = inventoryStacks().stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                    && category.equals(MinecraftItemIdentity.boxCategory(stack))
                    && !MinecraftItemIdentity.full(stack)
                    && owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                    .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
            requestedBox = !box.isEmpty();
            boxSource = target;
            if (box.isEmpty()) {
                box = targetContents.stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                        && category.equals(MinecraftItemIdentity.boxCategory(stack))
                        && !MinecraftItemIdentity.full(stack)
                        && !packedBoxes.contains(MinecraftItemIdentity.key(stack)))
                        .max(Comparator.comparingInt(MinecraftStorageAutomation::insideCount))
                        .map(ItemStack::copy).orElse(ItemStack.EMPTY);
            }
            if (box.isEmpty() && !checkedPartial && !partialSources.isEmpty()) {
                checkedPartial = true;
                findPartialBox(partialSources, 0, category, packedBoxes, (source, found) -> {
                    boxSource = source;
                    box = found;
                    continueBoxChoice();
                });
            } else {
                continueBoxChoice();
            }
        }

        private void continueBoxChoice() {
            ItemStack supplied = ownedEmptyBox(owned());
            if (box.isEmpty() && !supplied.isEmpty()) {
                box = supplied;
                requestedBox = true;
            }
            int inside = box.isEmpty() ? 0 : insideCount(box);
            int carried = sum(rawOwned());
            targetExcessRemaining = StoragePackingPlanner.targetItemsForPacking(policy, carried,
                    rawCount(targetContents), looseLimit, inside, stackSize);
            int available = carried + targetExcessRemaining;
            if (!StoragePackingPlanner.canPack(policy.allowPartialBoxes(), available,
                    inside, stackSize)) {
                if (requestedBox && policy.allowPartialBoxes()) {
                    storeBox();
                } else {
                    defer();
                }
                return;
            }
            preferredSite = chooseSite(context, target, policy.packingArea(), configuration);
            if (!box.isEmpty()) {
                if (requestedBox) {
                    placeBox();
                } else {
                    String key = MinecraftItemIdentity.key(box);
                    transfer(boxSource, Map.of(key, 1), true, () -> {
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
                int amount = Math.min(targetExcessRemaining, sum(raw));
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
            storePackedBox(target, box, updated -> {
                packedBoxes.add(key);
                checkedPartial = false;
                skipLooseDeposit = false;
                if (policy.mode() == StorageMode.BOX_ONLY) packBoxesOnly();
                else inspectContents(updated);
            });
        }

        private void defer() {
            if (!context.workspace().isClassificationStorage(target)) {
                context.workspace().markTargetUnavailable(target);
                context.workspace().setCacheHasMore(false);
            }
            finishCategory();
        }

        private void finishCategory() {
            if (policy.refillLoose() && policy.mode() == StorageMode.MIXED) {
                steps.add(new Step(new RefillLoose(context, target, category, targetContents),
                        this::nextCategory));
            } else {
                nextCategory();
            }
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
                } else {
                    stashUnusedSupplyBox(0);
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

    private final class RefillLoose extends Session {
        private final ContainerTarget target;
        private final String category;
        private ContainerTarget boxTarget;
        private ContainerTarget boxSource;
        private List<ContainerTarget> partialSources = List.of();
        private ItemStack box;
        private int stackSize;
        private Map<String, Integer> extracted = Map.of();
        private boolean madeProgress;
        private boolean initialized;
        private final List<ItemStack> initialContents;

        RefillLoose(TaskExecutionContext context, ContainerTarget target, String category,
                List<ItemStack> initialContents) {
            super(context);
            this.target = target;
            this.category = category;
            this.initialContents = initialContents;
        }

        @Override
        void initialize() {
            initialized = true;
            partialSources = partialBoxContainers(context, target.storagePolicy()).stream()
                    .filter(source -> !sameContainer(source, target)).toList();
            inspect(initialContents);
        }

        @Override
        public TaskModuleResult tick() {
            return initialized ? super.tick() : super.start();
        }

        private void inspect(List<ItemStack> contents) {
            if (stackSize == 0) {
                stackSize = contents.stream().filter(stack -> !stack.isEmpty()
                        && !MinecraftItemIdentity.isBox(stack)
                        && category.equals(MinecraftItemIdentity.base(stack)))
                        .findFirst().map(ItemStack::getMaxStackSize).orElse(0);
            }
            if (StoragePackingPlanner.looseLimit(target.storagePolicy(), contents.size(), 1) == 0) {
                return;
            }
            if (stackSize > 0 && refillAmount(contents, Integer.MAX_VALUE) == 0) return;
            boxSource = target;
            box = contents.stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                    && category.equals(MinecraftItemIdentity.boxCategory(stack)))
                    .min(Comparator.comparingInt(MinecraftStorageAutomation::insideCount))
                    .map(ItemStack::copy).orElse(ItemStack.EMPTY);
            if (!box.isEmpty()) {
                beginRefill(contents);
            } else if (!partialSources.isEmpty()) {
                findPartialBox(partialSources, 0, category, Set.of(), (source, found) -> {
                    boxSource = source;
                    box = found;
                    if (!box.isEmpty()) beginRefill(contents);
                });
            }
        }

        private void beginRefill(List<ItemStack> contents) {
            stackSize = MinecraftItemIdentity.contents(box).nonEmptyItemCopyStream()
                    .findFirst().orElseThrow().getMaxStackSize();
            if (refillAmount(contents, insideCount(box)) == 0) {
                return;
            }
            BlockPosition site = chooseSite(context, target, target.storagePolicy().packingArea(),
                    configuration);
            String key = MinecraftItemIdentity.key(box);
            int before = owned().getOrDefault(key, 0);
            transfer(boxSource, Map.of(key, 1), true, () -> {
                if (owned().getOrDefault(key, 0) != before + 1) {
                    throw new IllegalStateException("补散货用潜影盒取出未确认");
                }
                madeProgress = false;
                var resource = new MinecraftShulkerAction.Resource(site, box);
                resources.put(owner(), resource);
                boxTarget = new ContainerTarget(site, "minecraft:shulker_box");
                steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, true),
                        this::extractLoose));
            });
        }

        private void extractLoose() {
            read(boxTarget, boxContents -> read(target, contents -> {
                Map<String, Integer> requested = take(categoryItems(boxContents),
                        refillAmount(contents, Integer.MAX_VALUE));
                Map<String, Integer> before = owned();
                transfer(boxTarget, requested, true, () -> {
                    Map<String, Integer> acquired = new LinkedHashMap<>();
                    requested.forEach((key, count) -> {
                        int moved = Math.min(count,
                                owned().getOrDefault(key, 0) - before.getOrDefault(key, 0));
                        if (moved > 0) acquired.put(key, moved);
                    });
                    extracted = Map.copyOf(acquired);
                    depositLoose();
                });
            }));
        }

        private void depositLoose() {
            read(target, contents -> {
                Map<String, Integer> requested = take(extracted,
                        refillAmount(contents, Integer.MAX_VALUE));
                Map<String, Integer> before = owned();
                transfer(target, requested, false, () -> {
                    Map<String, Integer> returned = new LinkedHashMap<>(extracted);
                    requested.forEach((key, count) -> {
                        int moved = Math.min(count,
                                before.getOrDefault(key, 0) - owned().getOrDefault(key, 0));
                        if (moved > 0) {
                            madeProgress = true;
                            returned.compute(key, (ignored, value) -> value - moved);
                        }
                    });
                    returned.values().removeIf(count -> count <= 0);
                    returnUnusedLoose(returned);
                });
            });
        }

        private void returnUnusedLoose(Map<String, Integer> returned) {
            Map<String, Integer> before = owned();
            transfer(boxTarget, returned, false, () -> {
                if (returned.entrySet().stream().anyMatch(entry ->
                        owned().getOrDefault(entry.getKey(), 0)
                                != before.getOrDefault(entry.getKey(), 0) - entry.getValue())) {
                    throw new IllegalStateException("补货余料尚未归还潜影盒，等待全局收尾");
                }
                var resource = resources.get(owner());
                recover(() -> {
                    box = resource.expected;
                    storeRemainder();
                });
            });
        }

        private void storeRemainder() {
            storePackedBox(target, box, contents -> {
                if (madeProgress) inspect(contents);
            });
        }

        private int refillAmount(List<ItemStack> contents, int available) {
            int otherSlots = (int) contents.stream().filter(stack -> !stack.isEmpty()
                    && !MinecraftItemIdentity.isBox(stack)
                    && !category.equals(MinecraftItemIdentity.base(stack))).count();
            return StoragePackingPlanner.refillCount(target.storagePolicy(), contents.size(),
                    stackSize, sum(categoryItems(contents)), otherSlots, available);
        }

        private Map<String, Integer> categoryItems(List<ItemStack> contents) {
            Map<String, Integer> items = new LinkedHashMap<>();
            contents.stream().filter(stack -> !stack.isEmpty()
                    && !MinecraftItemIdentity.isBox(stack)
                    && category.equals(MinecraftItemIdentity.base(stack))).forEach(stack ->
                            items.merge(MinecraftItemIdentity.key(stack), stack.getCount(),
                                    Math::addExact));
            return items;
        }
    }

    private final class Overflow extends Session {
        private boolean initialized;
        private final dev.stowling.v2.model.RegionDefinition cacheRegion;
        private List<ContainerTarget> destinations;
        private ContainerVisitPlan sources;
        private ContainerTarget currentSource;
        private ContainerTarget boxTarget;
        private ItemStack box;

        Overflow(TaskExecutionContext context, dev.stowling.v2.model.RegionDefinition cacheRegion) {
            super(context);
            this.cacheRegion = cacheRegion;
        }

        @Override
        void initialize() {
            initialized = true;
            if (cacheRegion == null && owned().isEmpty()) return;
            if (!cleanupAvailable()) {
                throw new IllegalStateException("溢出打包前请配置任务中断存放位置");
            }
            destinations = overflowContainers();
            if (destinations.isEmpty()) {
                throw new IllegalStateException("请先在公共设施配置溢出箱或溢出区域");
            }
            if (hasTemporaryResources(owner())) {
                throw new IllegalStateException("尚有未回收潜影盒，请先执行全局收尾");
            }
            List<ContainerTarget> caches = List.of();
            if (cacheRegion != null) {
                var scan = containerRegions.scanComplete(cacheRegion, Set.of(), 1_000_000);
                if (!scan.succeeded()) throw new IllegalStateException(scan.message());
                caches = scan.containers().stream().filter(target -> destinations.stream()
                        .noneMatch(destination -> destination.entrances().stream()
                                .anyMatch(target.entrances()::contains)))
                        .filter(target -> target.entrances().stream()
                                .noneMatch(configuration.emptyBoxContainers()::contains))
                        .filter(target -> context.workspace().cacheContents(target)
                                .map(items -> items.keySet().stream().anyMatch(key ->
                                        !ItemIdentity.isEmptyBox(key)))
                                .orElse(true)).toList();
            }
            sources = new ContainerVisitPlan(caches);
            packOwned();
        }

        private void packOwned() {
            ItemStack filled = inventoryStacks().stream().filter(MinecraftItemIdentity::isBox)
                    .filter(stack -> !isEmptyBox(stack))
                    .filter(stack -> owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                    .findFirst().orElse(ItemStack.EMPTY);
            if (!filled.isEmpty()) {
                box = filled;
                storeOverflowBox(0);
                return;
            }
            if (!rawOwned().isEmpty()) {
                box = ownedEmptyBox(owned());
                if (box.isEmpty()) {
                    withdrawEmptyBox(0, supplied -> {
                        box = supplied;
                        placeOverflowBox();
                    });
                } else {
                    placeOverflowBox();
                }
                return;
            }
            ItemStack empty = ownedEmptyBox(owned());
            if (!empty.isEmpty()) {
                box = empty;
                storeOverflowBox(0);
            } else if (currentSource != null) {
                drainSource();
            } else {
                visitSource();
            }
        }

        @Override
        public TaskModuleResult tick() {
            return initialized ? super.tick() : super.start();
        }

        private Map<String, Integer> rawOwned() {
            Map<String, Integer> items = new LinkedHashMap<>();
            owned().forEach((key, count) -> {
                if (!ItemIdentity.isBox(key)) items.put(key, count);
            });
            return items;
        }

        private void placeOverflowBox() {
            BlockPosition site = chooseSite(context, destinations.getFirst(), null, configuration);
            var resource = new MinecraftShulkerAction.Resource(site, box);
            resources.put(owner(), resource);
            boxTarget = new ContainerTarget(site, "minecraft:shulker_box");
            steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, true),
                    this::fillOverflowBox));
        }

        private void fillOverflowBox() {
            int before = sum(rawOwned());
            transfer(boxTarget, rawOwned(), false, () -> {
                if (sum(rawOwned()) >= before) {
                    throw new IllegalStateException("溢出潜影盒未能装入物品，等待全局收尾");
                }
                var resource = resources.get(owner());
                recover(() -> {
                    box = resource.expected;
                    storeOverflowBox(0);
                });
            });
        }

        private void storeOverflowBox(int index) {
            if (index >= destinations.size()) {
                throw new IllegalStateException("溢出设施已满或不可达，打包物品已保留，等待收尾");
            }
            String key = MinecraftItemIdentity.key(box);
            int before = owned().getOrDefault(key, 0);
            var access = new MinecraftStorageAccess(minecraft, context, destinations.get(index),
                    Map.of(key, 1), false, false);
            Runnable after = () -> {
                if (owned().getOrDefault(key, 0) == before - 1) {
                    packOwned();
                } else {
                    storeOverflowBox(index + 1);
                }
            };
            steps.add(new Step(access, after, after));
        }

        private void visitSource() {
            BlockPos feet = minecraft.player.blockPosition();
            currentSource = sources.next(new BlockPosition(feet.getX(), feet.getY(), feet.getZ()))
                    .orElse(null);
            if (currentSource != null) drainSource();
        }

        private void drainSource() {
            read(currentSource, contents -> {
                Set<String> keys = contents.stream().filter(stack -> !stack.isEmpty())
                        .filter(stack -> !isEmptyBox(stack)).map(MinecraftItemIdentity::key)
                        .collect(java.util.stream.Collectors.toSet());
                if (keys.isEmpty()) {
                    currentSource = null;
                    visitSource();
                    return;
                }
                var drain = new ContainerBatchWithdrawal(context, currentSource, keys);
                steps.add(new Step(drain, () -> {
                    if (drain.withdrawn().isEmpty()) {
                        throw new IllegalStateException("背包空间不足，缓存溢出物品尚未取出");
                    }
                    if (!drain.matchingItemsRemain()) currentSource = null;
                    packOwned();
                }));
            });
        }

        @Override
        String completionMessage() {
            return "无法处理的缓存物品已打包送往溢出设施";
        }
    }

    private final class Cleanup extends Session {
        private List<ContainerTarget> targets;
        private final boolean overflowEnabled;
        private boolean overflowAttempted;
        private String lastFailure = "";

        Cleanup(TaskExecutionContext context, boolean overflowEnabled) {
            super(context);
            this.overflowEnabled = overflowEnabled;
        }

        @Override
        void initialize() {
            recover(this::resolveDestinations);
        }

        private void resolveDestinations() {
            List<ContainerTarget> destinations = taskCleanupContainers(context, configuration);
            targets = destinations.stream()
                    .sorted(Comparator.comparingDouble(target -> distance(target.position())))
                    .toList();
            if (targets.isEmpty() && !overflowEnabled) {
                throw new IllegalStateException("任务未配置可用的中断存放位置，请配置后重试");
            }
            depositAll(0);
        }

        private void depositAll(int index) {
            context.inventory().observe();
            context.inventory().claimUnexpected(context.workspace().inventoryAccount(), Set.of());
            if (owned().isEmpty()) {
                return;
            }
            if (index >= targets.size()) {
                if (overflowEnabled && !overflowAttempted) {
                    overflowAttempted = true;
                    steps.add(new Step(new Overflow(context, null), () -> depositAll(index)));
                    return;
                }
                throw new IllegalStateException("任务指定存放位置未能接收剩余 " + sum(owned())
                        + " 件任务物品，收尾受阻"
                        + (lastFailure.isEmpty() ? "" : "；最后一次失败：" + lastFailure));
            }
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    targets.get(index), owned(), false, false);
            steps.add(new Step(access, () -> depositAll(index + 1),
                    () -> {
                        lastFailure = access.tick().message();
                        depositAll(index + 1);
                    }));
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
