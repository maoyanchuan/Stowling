package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerVisitPlan;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.EmptyBoxSupplyPlan;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.automation.ShulkerTransferPlanner;
import dev.stowling.automation.TemporaryContainerResolver;
import dev.stowling.automation.CachePackingBatchPlan;
import dev.stowling.automation.PartialBoxSourcePlan;
import dev.stowling.domain.BlockPosition;
import dev.stowling.inventory.RecordedStorageCapacity;
import dev.stowling.inventory.WarehouseInventory;
import dev.stowling.v2.config.StorageSettingsStore;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.MaterialRequirements;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.StorageAutomationSettings;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePackingPlanner;
import dev.stowling.v2.model.StoragePolicy;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.runtime.modules.ContainerBatchWithdrawal;
import dev.stowling.v2.runtime.modules.StorageAutomationPort;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import dev.stowling.v2.runtime.modules.TaskModuleState;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemContainerContents;

/** Shared packing and cleanup coordinator. One active task owns the client's interaction slot. */
public final class MinecraftStorageAutomation implements StorageAutomationPort {
    private final Minecraft minecraft;
    private final StorageSettingsStore settings;
    private final dev.stowling.automation.ContainerRegionPort containerRegions;
    private final Supplier<WarehouseInventory> warehouse;
    private final Supplier<WarehouseInventory> materialWarehouse;
    private final Map<UUID, MinecraftShulkerAction.Resource> resources = new LinkedHashMap<>();
    private final Map<TaskRunWorkspace, Map<BlockPosition, CacheSnapshot>> cacheSnapshotsByRun =
            new WeakHashMap<>();

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
        this(minecraft, settings, containerRegions, warehouse, warehouse);
    }

    public MinecraftStorageAutomation(Minecraft minecraft, StorageSettingsStore settings,
            dev.stowling.automation.ContainerRegionPort containerRegions,
            Supplier<WarehouseInventory> warehouse, Supplier<WarehouseInventory> materialWarehouse) {
        this.minecraft = minecraft;
        this.settings = settings;
        this.containerRegions = containerRegions;
        this.warehouse = warehouse;
        this.materialWarehouse = materialWarehouse;
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
    public void releaseTemporaryResources(UUID owner) {
        resources.remove(owner);
    }

    @Override
    public boolean isRecoveryDrop(DroppedItem item) {
        return resources.values().stream().anyMatch(resource -> resource.placed
                && MinecraftShulkerAction.isRecoveryDrop(minecraft, resource, item));
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
    public TaskModuleExecutor collectMaterials(TaskExecutionContext context, TaskStep step) {
        return new MaterialCollection(context,
                ResourceReference.parse(step.parameters().get("targetRef")),
                MaterialRequirements.parse(step.parameters().get(MaterialRequirements.PARAMETER)));
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
        if (context.workspace().cacheContents(cache).isEmpty()) {
            return Optional.of(new Session(context) {
                @Override
                void initialize() {
                    readCache(cache, contents -> {
                        unpackMixedBox(context, cache).ifPresent(action ->
                                steps.add(new Step(action, () -> {})));
                    });
                }
            });
        }
        Set<String> present = context.workspace().cacheContents(cache)
                .map(Map::keySet).orElse(Set.of());
        if (context.workspace().unstackableClassification()) {
            boolean hasFilledBox = present.stream().anyMatch(key ->
                    ItemIdentity.isBox(key) && !ItemIdentity.isEmptyBox(key));
            return hasFilledBox && !context.workspace().remainingCacheCategories(cache).isEmpty()
                    ? Optional.of(new CacheBoxBatch(context, cache)) : Optional.empty();
        }
        Set<String> keys = context.workspace().mixedBoxWithdrawalItems(cache, present);
        List<String> ordered = new ArrayList<>(context.workspace().classificationItemOrder(keys));
        keys.stream().filter(key -> !ordered.contains(key)).forEach(ordered::add);
        for (String key : ordered) {
            if (ItemIdentity.isEmptyBox(key) || ItemIdentity.isMixedBox(key)) continue;
            String category = ItemIdentity.category(key);
            List<ContainerTarget> targets = context.workspace().classificationTargets(category);
            if (targets.isEmpty()) continue;
            ContainerTarget destination = targets.getFirst();
            if (destination.storagePolicy().mode() != StorageMode.NORMAL) {
                return Optional.of(new CachePackingBatch(context, cache, category, destination));
            }
        }
        return keys.stream().anyMatch(key -> ItemIdentity.isBox(key) && !ItemIdentity.isEmptyBox(key))
                || present.stream().anyMatch(ItemIdentity::isMixedBox)
                || context.workspace().hasExactClassifications() && present.stream()
                        .anyMatch(key -> ItemIdentity.isBox(key) && !ItemIdentity.isEmptyBox(key))
                ? Optional.of(new CacheBoxBatch(context, cache)) : Optional.empty();
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
    public Set<String> unstackableItems(TaskExecutionContext context, ContainerTarget cache) {
        CacheSnapshot snapshot = cacheSnapshotsByRun.getOrDefault(context.workspace(), Map.of())
                .get(cache.position());
        if (snapshot == null) {
            throw new IllegalStateException("请先盘点缓存容器库存");
        }
        return snapshot.stacks().stream().flatMap(stack -> MinecraftItemIdentity.isBox(stack)
                        ? MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream()
                        : java.util.stream.Stream.of(stack))
                .filter(MinecraftStorageAutomation::isUnstackableItem)
                .map(MinecraftItemIdentity::key)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static boolean isUnstackableItem(ItemStack stack) {
        return !stack.isEmpty() && !MinecraftItemIdentity.isBox(stack)
                && stack.getMaxStackSize() == 1;
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
            StoragePolicy policy, StorageAutomationSettings configuration) {
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
                containerRegions, Set.copyOf(configuration.emptyBoxContainers()),
                "未满盒存放");
    }

    private static boolean sameContainer(ContainerTarget first, ContainerTarget second) {
        return first.entrances().stream().anyMatch(second.entrances()::contains);
    }

    private record CacheSnapshot(Map<String, Integer> counts, List<ItemStack> stacks,
            Map<String, Integer> stackSizes, Set<String> fullBoxKeys) {
        private static final CacheSnapshot EMPTY = new CacheSnapshot(
                Map.of(), List.of(), Map.of(), Set.of());
    }

    private CacheSnapshot recordCacheContents(TaskExecutionContext context, ContainerTarget target,
            List<ItemStack> stacks) {
        List<ItemStack> occupied = stacks.stream().filter(stack -> !stack.isEmpty()).toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, Integer> stackSizes = new LinkedHashMap<>();
        Set<String> fullBoxKeys = new LinkedHashSet<>();
        for (ItemStack stack : occupied) {
            String key = MinecraftItemIdentity.key(stack);
            counts.merge(key, stack.getCount(), Math::addExact);
            if (stackSizes.put(key, stack.getMaxStackSize()) == null
                    && ItemIdentity.isBox(key) && MinecraftItemIdentity.full(stack)) {
                fullBoxKeys.add(key);
            }
        }
        CacheSnapshot snapshot = new CacheSnapshot(
                Map.copyOf(counts), occupied, Map.copyOf(stackSizes), Set.copyOf(fullBoxKeys));
        context.workspace().recordCacheContents(target, snapshot.counts());
        cacheSnapshotsByRun.computeIfAbsent(context.workspace(), ignored -> new LinkedHashMap<>())
                .put(target.position(), snapshot);
        return snapshot;
    }

    private Optional<List<ItemStack>> confirmedCacheContents(TaskExecutionContext context,
            ContainerTarget target) {
        return confirmedCacheSnapshot(context, target).map(CacheSnapshot::stacks);
    }

    private Optional<CacheSnapshot> confirmedCacheSnapshot(TaskExecutionContext context,
            ContainerTarget target) {
        CacheSnapshot snapshot = cacheSnapshotsByRun.getOrDefault(context.workspace(), Map.of())
                .get(target.position());
        return context.workspace().cacheContents(target)
                .filter(counts -> snapshot != null && counts.equals(snapshot.counts()))
                .map(ignored -> snapshot);
    }

    @Override
    public List<ContainerTarget> partialBoxSources(TaskExecutionContext context,
            List<ContainerTarget> targets) {
        List<ContainerTarget> sources = new ArrayList<>();
        Set<String> visited = new java.util.HashSet<>();
        for (ContainerTarget target : targets) {
            StoragePolicy policy = target.storagePolicy();
            if (policy.mode() != StorageMode.NORMAL && policy.partialBoxDestinationRef() != null
                    && visited.add(policy.partialBoxDestinationRef())) {
                sources.addAll(partialBoxContainers(context, policy, settings.resolved()));
            }
        }
        return EmptyBoxSupplyPlan.sources(sources, null, List.of());
    }

    private abstract class Session implements TaskModuleExecutor {
        final TaskExecutionContext context;
        final StorageAutomationSettings configuration = settings.resolved();
        final Deque<Step> steps = new ArrayDeque<>();
        Step current;
        boolean stopping;
        TaskModuleResult terminal;
        final Map<BlockPosition, CacheSnapshot> cacheSnapshots;
        private boolean initialized;

        Session(TaskExecutionContext context) {
            this(context, null);
        }

        Session(TaskExecutionContext context, Map<BlockPosition, CacheSnapshot> cacheSnapshots) {
            this.context = context;
            this.cacheSnapshots = cacheSnapshots;
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
            if (initialized) return tick();
            initialized = true;
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
            if (!initialized) return start();
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
                    if (!stopping && current.action instanceof MinecraftStorageAccess access) {
                        refreshCacheSnapshot(access);
                    }
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

        private void refreshCacheSnapshot(MinecraftStorageAccess access) {
            ContainerTarget target = access.target();
            boolean inPackingPlan = cacheSnapshots != null
                    && cacheSnapshots.containsKey(target.position());
            List<ItemStack> contents = access.contents();
            if (contents.isEmpty()) return;
            if (!inPackingPlan && context.workspace().cacheContents(target).isEmpty()
                    && context.workspace().containerScan().stream()
                            .flatMap(scan -> scan.containers().stream())
                            .noneMatch(cache -> sameContainer(cache, target))) return;
            CacheSnapshot snapshot = recordCacheContents(context, target, contents);
            if (inPackingPlan) cacheSnapshots.put(target.position(), snapshot);
        }

        final void read(ContainerTarget target, Consumer<List<ItemStack>> after) {
            read(target, after, null);
        }

        final void read(ContainerTarget target, Consumer<List<ItemStack>> after, Runnable failed) {
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    target, Map.of(), false, true);
            steps.add(new Step(access, () -> after.accept(access.contents()), failed));
        }

        final void readCache(ContainerTarget target, Consumer<List<ItemStack>> after) {
            read(target, contents -> {
                if (confirmedCacheContents(context, target).isEmpty()) {
                    recordCacheContents(context, target, contents);
                }
                after.accept(contents);
            });
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
            Consumer<List<ItemStack>> select = contents -> {
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
            };
            confirmedCacheContents(context, source).ifPresentOrElse(select,
                    () -> read(source, select,
                            () -> findPartialBox(sources, index + 1, category, skipped, after)));
        }

        final void storePackedBox(ContainerTarget target, ItemStack box,
                Consumer<List<ItemStack>> after) {
            storePackedBox(target, box, List.of(), after);
        }

        final void storePackedBox(ContainerTarget target, ItemStack box,
                List<ContainerTarget> stagingFallbacks, Consumer<List<ItemStack>> after) {
            if (isEmptyBox(box)) {
                storeEmptyBox(box, emptyBoxReturns(null), 0, () -> {
                    if (target.storagePolicy().mode() == StorageMode.BOX_ONLY) {
                        after.accept(List.of());
                    } else {
                        read(target, after);
                    }
                });
                return;
            }
            List<ContainerTarget> destinations = MinecraftItemIdentity.full(box)
                    || target.storagePolicy().partialBoxDestinationRef() == null
                    ? List.of(target)
                    : partialBoxContainers(context, target.storagePolicy(), configuration);
            if (!stagingFallbacks.isEmpty()) {
                List<ContainerTarget> candidates = new ArrayList<>(destinations);
                candidates.addAll(stagingFallbacks);
                destinations = EmptyBoxSupplyPlan.sources(candidates, null, List.of());
            }
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

        final int remainingBatchSlots() {
            Map<String, Integer> carried = owned();
            if (carried.isEmpty()) return StoragePackingPlanner.BOX_SLOTS;
            return ShulkerTransferPlanner.remainingBatchSlots(carried, inventoryStackSizes(),
                    StoragePackingPlanner.BOX_SLOTS);
        }

        final List<ContainerTarget> emptyBoxReturns(ContainerTarget preferred) {
            return emptyBoxReturnsForSources(preferred == null ? List.of() : List.of(preferred));
        }

        final List<ContainerTarget> emptyBoxReturnsForSources(List<ContainerTarget> preferred) {
            Set<BlockPosition> storagePositions = context.workspace().acceptedItemsByContainer()
                    .keySet().stream().flatMap(target -> target.entrances().stream())
                    .collect(java.util.stream.Collectors.toSet());
            List<ContainerTarget> sources = new ArrayList<>(preferred);
            sources.addAll(emptyBoxSources(context));
            return EmptyBoxSupplyPlan.returns(null, sources,
                    taskCleanupContainers(context, configuration), storagePositions,
                    context.workspace().partialBoxStoragePositions());
        }

        final void returnEmptyBoxes(Runnable after) {
            returnEmptyBoxes(emptyBoxReturns(null), after);
        }

        final void returnEmptyBoxes(List<ContainerTarget> destinations, Runnable after) {
            ItemStack empty = ownedEmptyBox(owned());
            if (empty.isEmpty()) {
                after.run();
            } else {
                storeEmptyBox(empty, destinations, 0, () -> returnEmptyBoxes(destinations, after));
            }
        }

        final void storeEmptyBox(ItemStack empty, List<ContainerTarget> destinations, int index,
                Runnable after) {
            if (index >= destinations.size()) {
                throw new IllegalStateException("缓存和空盒供应箱无法接收空潜影盒，等待收尾");
            }
            String key = MinecraftItemIdentity.key(empty);
            int before = owned().getOrDefault(key, 0);
            var access = new MinecraftStorageAccess(minecraft, context, destinations.get(index),
                    Map.of(key, 1), false, false);
            Runnable confirmed = () -> {
                if (owned().getOrDefault(key, 0) == before - 1) after.run();
                else storeEmptyBox(empty, destinations, index + 1, after);
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
            Runnable breakBox = () -> {
                if (!MinecraftShulkerAction.hasRecoveryCapacity(minecraft, resource.expected)
                        && !MinecraftShulkerAction.canConfirmRecovery(minecraft, resource)) {
                    makeRecoverySpace(resource, 0, after);
                    return;
                }
                steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, false),
                        () -> {
                            resources.remove(owner());
                            after.run();
                        }));
            };
            if (!resource.broken && resource.receipt.begun()
                    && minecraft.level.getBlockState(position).isAir()) {
                resource.broken = true;
            }
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

        private void makeRecoverySpace(MinecraftShulkerAction.Resource resource, int index,
                Runnable after) {
            List<ContainerTarget> destinations = taskCleanupContainers(context, configuration)
                    .stream().filter(target -> !target.entrances().contains(resource.position))
                    .toList();
            ItemStack movable = inventoryStacks().stream().filter(stack ->
                    owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) >= stack.getCount())
                    .min(Comparator.comparingInt(stack -> MinecraftItemIdentity.isBox(stack) ? 1 : 0))
                    .orElse(ItemStack.EMPTY);
            if (movable.isEmpty() || index >= destinations.size()) {
                throw new IllegalStateException("背包没有空位回收潜影盒，且任务物品无法暂存；"
                        + "请腾出一格后重试收尾");
            }
            String key = MinecraftItemIdentity.key(movable);
            int before = owned().getOrDefault(key, 0);
            var access = new MinecraftStorageAccess(minecraft, context, destinations.get(index),
                    Map.of(key, movable.getCount()), false, false);
            Runnable confirmed = () -> {
                int moved = before - owned().getOrDefault(key, 0);
                if (moved > 0) {
                    resource.receipt.recordRemoval(key, moved);
                    recover(after);
                } else {
                    makeRecoverySpace(resource, index + 1, after);
                }
            };
            steps.add(new Step(access, confirmed, confirmed));
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

    private final class MaterialCollection extends Session {
        private final ResourceReference destination;
        private final Map<String, Integer> needed;
        private final Map<String, String> materialByKey = new LinkedHashMap<>();
        private final Map<BlockPosition, Map<String, Integer>> observedDeliveries =
                new LinkedHashMap<>();
        private List<ContainerTarget> destinations;
        private List<ContainerTarget> sources;
        private AreaBounds destinationBounds;
        private String dimension;
        private String warehouseProblem;
        private int sourceIndex;
        private int unavailableSources;
        private boolean returningBox;
        private boolean stopAfterBox;

        MaterialCollection(TaskExecutionContext context, ResourceReference destination,
                Map<String, Integer> required) {
            super(context);
            this.destination = destination;
            needed = new LinkedHashMap<>(required);
        }

        @Override
        void initialize() {
            if (minecraft.level == null || minecraft.player == null) {
                throw new IllegalStateException("请先进入仓库所在世界");
            }
            dimension = minecraft.level.dimension().identifier().toString();
            List<BlockPosition> points = List.of();
            List<RegionDefinition> regions = List.of();
            if (destination.type() == ResourceType.COORDINATE) {
                var point = context.coordinates().get(destination.uuid());
                if (point.dimension() != null && !point.dimension().equals(dimension)) {
                    throw new IllegalStateException("请先进入交付容器所在维度");
                }
                points = List.of(new BlockPosition(point.x(), point.y(), point.z()));
            } else {
                var region = context.regions().get(destination.uuid());
                regions = List.of(region);
                destinationBounds = new AreaBounds(region.firstX(), region.firstY(),
                        region.firstZ(), region.secondX(), region.secondY(), region.secondZ());
            }
            destinations = TemporaryContainerResolver.resolve(points, regions, containerRegions,
                    Set.copyOf(configuration.emptyBoxContainers()), "材料交付");
            if (destinations.isEmpty()) {
                throw new IllegalStateException("交付区域没有可用的箱子或木桶");
            }
            destinations.forEach(target -> observedDeliveries.put(target.position(),
                    context.workspace().progress().deliveredTo(target.position())));
            inspectDestination(0);
        }

        @Override
        public TaskModuleResult tick() {
            if (dimension != null && (minecraft.level == null
                    || !dimension.equals(minecraft.level.dimension().identifier().toString()))) {
                cancel();
                terminal = TaskModuleResult.failed("已离开材料收集所在维度");
                return terminal;
            }
            return super.tick();
        }

        private void inspectDestination(int index) {
            if (index < destinations.size() && !needed.isEmpty()) {
                read(destinations.get(index), contents -> {
                    contents.forEach(stack -> {
                        credit(material(stack), stack.getCount());
                        if (MinecraftItemIdentity.isBox(stack)) {
                            MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream()
                                    .forEach(inner -> credit(material(inner), inner.getCount()));
                        }
                    });
                    inspectDestination(index + 1);
                });
            } else if (!needed.isEmpty()) {
                planSources();
            }
        }

        private void planSources() {
            WarehouseInventory stock = materialWarehouse.get();
            if (stock == null || !"READY".equals(stock.state())) {
                warehouseProblem = stock == null ? "仓库库存不可用" : stock.message();
                finishShort();
                return;
            }
            sources = stock.containers().stream()
                    .filter(container -> dimension.equals(container.dimension()))
                    .filter(container -> container.items().stream().anyMatch(entry ->
                            needed.containsKey(entry.itemId())
                                    || needed.containsKey(MaterialRequirements.WATER_BOTTLE)
                                            && "minecraft:potion".equals(entry.itemId())))
                    .filter(container -> container.positions().stream().noneMatch(
                            configuration.emptyBoxContainers()::contains))
                    .filter(container -> destinationBounds == null
                            || container.positions().stream()
                                    .noneMatch(destinationBounds::contains))
                    .map(container -> new ContainerTarget(container.positions().getFirst(),
                            "container", null, null, container.positions().size() > 1
                                    ? container.positions().get(1) : null))
                    .filter(source -> destinations.stream().noneMatch(
                            target -> sameContainer(source, target)))
                    .sorted(Comparator.comparingDouble(MinecraftStorageAutomation.this
                            ::storageDistance))
                    .toList();
            nextSource();
        }

        private void nextSource() {
            if (needed.isEmpty()) {
                return;
            }
            if (sourceIndex >= sources.size()) {
                finishShort();
                return;
            }
            inspectSource(sources.get(sourceIndex++));
        }

        private void inspectSource(ContainerTarget source) {
            if (needed.isEmpty()) {
                return;
            }
            read(source, contents -> takeFrom(source, contents), this::skipSource);
        }

        private void takeFrom(ContainerTarget source, List<ItemStack> contents) {
            Map<String, Integer> wanted = matching(contents);
            if (!wanted.isEmpty()) {
                withdraw(source, wanted, () -> {
                    if (!owned().isEmpty()) {
                        deliver(0, () -> inspectSource(source));
                    } else {
                        unpack(source, contents);
                    }
                }, this::skipSource);
            } else {
                unpack(source, contents);
            }
        }

        private void unpack(ContainerTarget source, List<ItemStack> contents) {
            ItemStack box = contents.stream().filter(MinecraftItemIdentity::isBox)
                    .filter(stack -> !matching(MinecraftItemIdentity.contents(stack)
                            .nonEmptyItemCopyStream().toList()).isEmpty())
                    .findFirst().orElse(ItemStack.EMPTY);
            if (box.isEmpty()) {
                nextSource();
                return;
            }
            if (!cleanupAvailable()) {
                throw new IllegalStateException("拆取盒装材料前请配置任务中断存放位置");
            }
            if (minecraft.player.getInventory().getNonEquipmentItems().stream()
                    .noneMatch(ItemStack::isEmpty)) {
                skipSource();
                return;
            }
            BlockPosition site;
            try {
                site = chooseSite(context, source, null, configuration);
            } catch (IllegalStateException exception) {
                skipSource();
                return;
            }
            String key = MinecraftItemIdentity.key(box);
            returningBox = true;
            var takeBox = new MinecraftStorageAccess(minecraft, context, source,
                    Map.of(key, 1), true, false);
            steps.add(new Step(takeBox, () -> {
                if (takeBox.remaining().getOrDefault(key, 0) != 0) {
                    returningBox = false;
                    skipSource();
                    return;
                }
                var resource = new MinecraftShulkerAction.Resource(site, box);
                resources.put(owner(), resource);
                var boxTarget = new ContainerTarget(site, "minecraft:shulker_box");
                steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, true),
                        () -> read(boxTarget, items -> takeBoxMaterials(
                                source, boxTarget, resource, items))));
            }));
        }

        private void takeBoxMaterials(ContainerTarget source, ContainerTarget boxTarget,
                MinecraftShulkerAction.Resource resource, List<ItemStack> contents) {
            var inventory = minecraft.player.getInventory().getNonEquipmentItems();
            List<ShulkerTransferPlanner.Slot> occupied = inventory.stream()
                    .filter(stack -> !stack.isEmpty())
                    .map(stack -> new ShulkerTransferPlanner.Slot(MinecraftItemIdentity.key(stack),
                            stack.getCount(), stack.getMaxStackSize())).toList();
            Map<String, Integer> sizes = new LinkedHashMap<>();
            contents.stream().filter(stack -> !stack.isEmpty()).forEach(stack ->
                    sizes.put(MinecraftItemIdentity.key(stack), stack.getMaxStackSize()));
            var fits = ShulkerTransferPlanner.fittingInventory(occupied, inventory.size() - 1,
                    matching(contents), sizes);
            withdraw(boxTarget, fits, () -> recover(() -> {
                String key = MinecraftItemIdentity.key(resource.expected);
                var putBack = new MinecraftStorageAccess(minecraft, context, source,
                        Map.of(key, 1), false, false);
                steps.add(new Step(putBack, () -> {
                    returningBox = false;
                    if (putBack.remaining().getOrDefault(key, 0) != 0) {
                        throw new IllegalStateException("仓库容器无法接收归还的潜影盒");
                    }
                    boolean deliveredDuringRecovery = creditDeliveries();
                    if (stopAfterBox) {
                        super.requestStop();
                    } else if (!owned().isEmpty()) {
                        deliver(0, () -> inspectSource(source));
                    } else if (deliveredDuringRecovery) {
                        inspectSource(source);
                    } else {
                        skipSource();
                    }
                }));
            }), null);
        }

        private void withdraw(ContainerTarget source, Map<String, Integer> items,
                Runnable after, Runnable failed) {
            var action = new MinecraftStorageAccess(minecraft, context, source, items, true, false);
            steps.add(new Step(action, after, failed == null ? null : () -> {
                if (owned().isEmpty()) {
                    failed.run();
                } else {
                    deliver(0, failed);
                }
            }));
        }

        private void deliver(int index, Runnable after) {
            Map<String, Integer> batch = owned();
            if (batch.isEmpty()) {
                after.run();
                return;
            }
            if (index >= destinations.size()) {
                throw new IllegalStateException("交付容器已满或不可用，剩余材料等待收尾");
            }
            var action = new MinecraftStorageAccess(minecraft, context, destinations.get(index),
                    batch, false, false);
            Runnable settled = () -> {
                creditDeliveries();
                deliver(index + 1, after);
            };
            steps.add(new Step(action, settled, settled));
        }

        private Map<String, Integer> matching(List<ItemStack> stacks) {
            Map<String, Integer> remaining = new LinkedHashMap<>(needed);
            Map<String, Integer> result = new LinkedHashMap<>();
            for (ItemStack stack : stacks) {
                String material = material(stack);
                int count = Math.min(remaining.getOrDefault(material, 0), stack.getCount());
                if (count > 0) {
                    String key = MinecraftItemIdentity.key(stack);
                    materialByKey.put(key, material);
                    result.merge(key, count, Math::addExact);
                    remaining.computeIfPresent(material, (ignored, left) -> left - count);
                }
            }
            return result;
        }

        private String material(ItemStack stack) {
            if (stack.isEmpty() || (MinecraftItemIdentity.isBox(stack) && !isEmptyBox(stack))) {
                return "";
            }
            var potion = stack.get(DataComponents.POTION_CONTENTS);
            if (potion != null && potion.is(Potions.WATER)
                    && MaterialRequirements.WATER_BOTTLE.equals(
                            MinecraftItemIdentity.base(stack) + "[water]")) {
                return MaterialRequirements.WATER_BOTTLE;
            }
            return MinecraftItemIdentity.base(stack);
        }

        private void credit(String material, int count) {
            if (material != null && count > 0) {
                needed.computeIfPresent(material, (ignored, left) ->
                        count < left ? left - count : null);
            }
        }

        private boolean creditDeliveries() {
            boolean changed = false;
            for (ContainerTarget target : destinations) {
                var position = target.position();
                var current = context.workspace().progress().deliveredTo(position);
                var previous = observedDeliveries.put(position, current);
                if (!current.equals(previous)) {
                    changed = true;
                    current.forEach((key, count) ->
                            credit(materialByKey.get(key), count - previous.getOrDefault(key, 0)));
                }
            }
            return changed;
        }

        private void skipSource() {
            unavailableSources++;
            nextSource();
        }

        private void finishShort() {
            String missing = needed.entrySet().stream()
                    .map(item -> item.getKey() + " × " + item.getValue())
                    .collect(java.util.stream.Collectors.joining("、"));
            String reason = warehouseProblem == null
                    ? unavailableSources == 0 ? "" : "（" + unavailableSources + " 个来源无法完成取料）"
                    : "（" + warehouseProblem + "）";
            terminal = TaskModuleResult.failed("仍缺材料" + reason + "：" + missing);
        }

        @Override
        public void requestStop() {
            if (returningBox && terminal == null) {
                stopAfterBox = true;
            } else {
                super.requestStop();
            }
        }

        @Override
        public boolean safeToStop() {
            return (!returningBox || terminal != null) && super.safeToStop();
        }
    }

    private final class CacheInventoryScan extends Session {
        private final ContainerVisitPlan visits;
        private int scanned;

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
            if (next == null) return;
            readCache(next, contents -> {
                scanned++;
                visitNext();
            });
        }

        @Override
        String completionMessage() {
            return "已完成缓存库存盘点，共读取 " + scanned + " 个容器";
        }
    }

    /** Consolidates at the cache, then hands a completed payload to the normal delivery route. */
    private final class CachePackingBatch extends Session {
        private static final int RESERVED_INVENTORY_SLOTS = 2;

        private final ContainerTarget initialCache;
        private final Set<String> preparedCategories = new LinkedHashSet<>();
        private String category;
        private ContainerTarget destination;
        private StoragePolicy policy;
        private List<ContainerTarget> sources;
        private List<ContainerTarget> partialSources = List.of();
        private int stackSize;
        private int looseDeficit;
        private int existingBoxSpace;
        private CachePackingBatchPlan plan;
        private boolean shareFullBox;

        CachePackingBatch(TaskExecutionContext context, ContainerTarget cache, String category,
                ContainerTarget destination) {
            super(context, new LinkedHashMap<>());
            initialCache = cache;
            this.category = category;
            this.destination = destination;
            policy = destination.storagePolicy();
        }

        @Override
        void initialize() {
            startCategory(partialBoxContainers(context, policy, configuration));
        }

        private void startCategory(List<ContainerTarget> configuredPartials) {
            preparedCategories.add(category);
            plan = null;
            List<ContainerTarget> candidates = new ArrayList<>();
            candidates.add(initialCache);
            candidates.addAll(context.workspace().cacheContainers());
            partialSources = PartialBoxSourcePlan.combine(List.of(), configuredPartials,
                    List.copyOf(context.workspace().acceptedItemsByContainer().keySet()));
            candidates.addAll(partialSources);
            sources = EmptyBoxSupplyPlan.sources(candidates, null, List.of());
            cacheSnapshots.clear();
            sources.forEach(source -> cacheSnapshots.put(source.position(), CacheSnapshot.EMPTY));
            scan(0, 0);
        }

        private void scan(int index, int scannedFullBoxes) {
            int fullBoxes = scannedFullBoxes;
            for (int cursor = index; cursor < sources.size()
                    && fullBoxes < StoragePackingPlanner.BOX_SLOTS; cursor++) {
                ContainerTarget source = sources.get(cursor);
                Optional<CacheSnapshot> confirmed = confirmedCacheSnapshot(context, source);
                if (confirmed.isEmpty()) {
                    int next = cursor + 1;
                    int fullBeforeRead = fullBoxes;
                    read(source, contents -> {
                        tracePackingSource(source, contents);
                        CacheSnapshot snapshot = cacheSnapshots.get(source.position());
                        scan(next, fullBeforeRead + fullCount(snapshot));
                    });
                    return;
                }
                CacheSnapshot snapshot = confirmed.orElseThrow();
                cacheSnapshots.put(source.position(), snapshot);
                fullBoxes += fullCount(snapshot);
            }
            stackSize = snapshots().stream().flatMap(stack -> MinecraftItemIdentity.isBox(stack)
                    ? MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream()
                    : java.util.stream.Stream.of(stack))
                    .filter(stack -> category.equals(MinecraftItemIdentity.base(stack)))
                    .findFirst().map(ItemStack::getMaxStackSize).orElse(64);
            looseDeficit = targetLooseDeficit();
            existingBoxSpace = recordedBoxSpace();
            prepareRound();
        }

        private void tracePackingSource(ContainerTarget source, List<ItemStack> contents) {
            dev.stowling.v2.runtime.TaskTrace.event("INVENTORY", "cache.packingSource",
                    "category=" + category + "; source=" + source.position()
                            + "; loose=" + sum(raw(contents)) + "; boxItems="
                            + contents.stream().filter(MinecraftItemIdentity::isBox)
                                    .filter(stack -> category.equals(
                                            MinecraftItemIdentity.boxCategory(stack)))
                                    .map(MinecraftStorageAutomation::insideCount).toList(),
                    0, false);
        }

        private List<ItemStack> snapshots() {
            return cacheSnapshots.values().stream()
                    .flatMap(snapshot -> snapshot.stacks().stream()).toList();
        }

        private int fullCount() {
            return cacheSnapshots.values().stream().mapToInt(this::fullCount).sum();
        }

        private int fullCount(CacheSnapshot snapshot) {
            return snapshot.fullBoxKeys().stream()
                    .filter(key -> category.equals(ItemIdentity.category(key)))
                    .mapToInt(snapshot.counts()::get).sum();
        }

        private Map<String, Integer> raw(List<ItemStack> contents) {
            Map<String, Integer> items = new LinkedHashMap<>();
            contents.stream().filter(stack -> !stack.isEmpty() && !MinecraftItemIdentity.isBox(stack)
                    && category.equals(MinecraftItemIdentity.base(stack))).forEach(stack ->
                            items.merge(MinecraftItemIdentity.key(stack), stack.getCount(),
                                    Math::addExact));
            return items;
        }

        private Map<String, Integer> rawOwned() {
            Map<String, Integer> items = new LinkedHashMap<>();
            owned().forEach((key, count) -> {
                if (!ItemIdentity.isBox(key) && category.equals(ItemIdentity.category(key))) {
                    items.put(key, count);
                }
            });
            return items;
        }

        private CachePackingRound packingRound(List<ItemStack> stock, List<ItemStack> partials,
                int looseItems, int partialItems, int fullBoxes, int largestPartial) {
            CachePackingBatchPlan fallback = calculatePlan(looseItems, partialItems, 0, fullBoxes);
            Map<String, Integer> loose = take(raw(stock), fallback.loosePackingBudget(looseItems));
            Map<String, Integer> available = new LinkedHashMap<>(loose);
            Map<String, Integer> withoutKeeper = new LinkedHashMap<>(loose);
            Map<String, Integer> sizes = new LinkedHashMap<>();
            stock.stream().filter(stack -> category.equals(MinecraftItemIdentity.base(stack)))
                    .forEach(stack -> sizes.put(MinecraftItemIdentity.key(stack),
                            stack.getMaxStackSize()));
            for (ItemStack box : partials) {
                boolean mixed = ItemIdentity.isMixedBox(MinecraftItemIdentity.key(box));
                MinecraftItemIdentity.contents(box).nonEmptyItemCopyStream().forEach(stack -> {
                    if (!category.equals(MinecraftItemIdentity.base(stack))) return;
                    String key = MinecraftItemIdentity.key(stack);
                    available.merge(key, stack.getCount() * box.getCount(), Math::addExact);
                    sizes.put(key, stack.getMaxStackSize());
                    if (mixed) {
                        withoutKeeper.merge(key, stack.getCount() * box.getCount(), Math::addExact);
                    }
                });
            }
            CachePackingRound best = null;
            int bestFill = 0;
            int bestInside = 0;
            for (ContainerTarget source : sources) {
                for (ItemStack stack : cacheSnapshots.get(source.position()).stacks()) {
                    if (!MinecraftItemIdentity.isBox(stack) || MinecraftItemIdentity.full(stack)
                            || !category.equals(MinecraftItemIdentity.boxCategory(stack))) continue;
                    int inside = insideCount(stack);
                    CachePackingBatchPlan candidate = calculatePlan(looseItems, partialItems,
                            inside, fullBoxes);
                    var load = candidate.compatibleLoad(boxSlots(stack), available, sizes,
                            largestPartial);
                    int fill = inside + sum(load);
                    if (!load.isEmpty() && (fill > bestFill
                            || fill == bestFill && inside > bestInside)) {
                        best = new CachePackingRound(new CachedBox(source, stack.copy()),
                                candidate, load, loose);
                        bestFill = fill;
                        bestInside = inside;
                    }
                }
            }
            if (best != null) return best;
            // With no compatible keeper, leave single-category partials in their boxes.
            return new CachePackingRound(null, fallback,
                    fallback.compatibleLoad(List.of(), withoutKeeper, sizes, largestPartial),
                    loose);
        }

        private CachePackingBatchPlan calculatePlan(int loose, int partial, int inside, int full) {
            return CachePackingBatchPlan.calculate(policy, loose, partial, inside, stackSize,
                    looseDeficit, full, existingBoxSpace, existingBoxSpace == 0);
        }

        private int largestPartial(List<ItemStack> stock) {
            return stock.stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                    && !MinecraftItemIdentity.full(stack)
                    && category.equals(MinecraftItemIdentity.boxCategory(stack)))
                    .mapToInt(MinecraftStorageAutomation::insideCount).max().orElse(0);
        }

        private void prepareRound() {
            int fullBoxes = fullCount();
            List<ItemStack> stock = snapshots();
            List<ItemStack> partials = stock.stream().filter(MinecraftItemIdentity::isBox)
                    .filter(stack -> !MinecraftItemIdentity.full(stack)
                            || ItemIdentity.isMixedBox(MinecraftItemIdentity.key(stack))).toList();
            int partialItems = partials.stream()
                    .mapToInt(stack -> sum(raw(MinecraftItemIdentity.contents(stack)
                            .nonEmptyItemCopyStream().toList())) * stack.getCount()).sum();
            int looseItems = sum(raw(stock));
            if (plan == null) {
                shareFullBox = StoragePackingPlanner.canPack(false,
                        Math.addExact(looseItems, partialItems), 0, stackSize)
                        && hasCompanionStock();
            }
            int largestBefore = largestPartial(partials);
            CachePackingRound round = packingRound(stock, partials, looseItems, partialItems,
                    fullBoxes, largestBefore);
            plan = round.plan();
            dev.stowling.v2.runtime.TaskTrace.event("DECISION", "cache.packBatch",
                    "category=" + category + "; looseReserved=" + plan.looseReserved()
                            + "; materialToPack=" + plan.materialToPack()
                            + "; boxes=" + plan.plannedBoxes()
                            + "; nextLoad=" + sum(round.load()));
            if ((shareFullBox && fullBoxes > 0) || fullBoxes >= remainingBatchSlots()
                    || round.load().isEmpty() || !cleanupAvailable()) {
                prepareReservedLoose();
                return;
            }
            CachedBox keeper = round.keeper();
            ContainerTarget staging = keeper == null ? initialCache : keeper.source();
            String keeperKey = keeper == null ? "" : MinecraftItemIdentity.key(keeper.box());
            int before = sum(rawOwned());
            var load = new CacheBoxBatch(context, staging, category,
                    keeper == null ? null : keeper.source(), keeperKey, sum(round.load()),
                    cacheSnapshots, sources, round.load(), round.loose());
            steps.add(new Step(load, () -> {
                if (sum(rawOwned()) <= before) {
                    prepareReservedLoose();
                    return;
                }
                StoragePolicy stagingPolicy = new StoragePolicy(StorageMode.BOX_ONLY, 0,
                        true, null, false, 0, null);
                ContainerTarget target = new ContainerTarget(staging.position(), staging.type(),
                        staging.standingArea(), stagingPolicy, staging.partner());
                ItemStack plannedKeeper = keeper == null ? ItemStack.EMPTY : keeper.box();
                var pack = new Packing(context, target, rawOwned(), plannedKeeper, cacheSnapshots);
                steps.add(new Step(pack, () -> {
                    if (!rawOwned().isEmpty()) {
                        returnUnusedRaw(0);
                    } else if (fullCount() > fullBoxes
                            || largestPartial(snapshots()) > largestBefore) {
                        prepareRound();
                    } else {
                        prepareReservedLoose();
                    }
                }));
            }));
        }

        private void returnUnusedRaw(int index) {
            if (rawOwned().isEmpty()) {
                prepareReservedLoose();
                return;
            }
            if (index >= sources.size()) {
                throw new IllegalStateException("打包余料无法归还缓存，交给任务收尾");
            }
            transfer(sources.get(index), rawOwned(), false, () -> returnUnusedRaw(index + 1));
        }

        private void prepareReservedLoose() {
            shareFullBox = shareFullBox && fullCount() > 0;
            gather(0);
        }

        private int rawDeliveryLimit() {
            if (existingBoxSpace > 0) {
                return Math.addExact(sum(raw(snapshots())), snapshots().stream()
                        .filter(stack -> MinecraftItemIdentity.isBox(stack)
                                && (!MinecraftItemIdentity.full(stack)
                                        || ItemIdentity.isMixedBox(MinecraftItemIdentity.key(stack))))
                        .mapToInt(stack -> sum(raw(MinecraftItemIdentity.contents(stack)
                                .nonEmptyItemCopyStream().toList()))).sum());
            }
            return plan == null ? 0 : plan.looseReserved();
        }

        private void prepareLoosePayload(int wanted) {
            if ((existingBoxSpace > 0 || policy.mode() == StorageMode.MIXED && policy.refillLoose())
                    && sum(rawOwned()) < wanted) {
                var load = new CacheBoxBatch(context, initialCache, category, null, "",
                        wanted - sum(rawOwned()), cacheSnapshots, sources, Map.of(), Map.of());
                steps.add(new Step(load, this::finishCategoryLoad));
            } else {
                finishCategoryLoad();
            }
        }

        private void gather(int index) {
            if (index >= sources.size()) {
                gatherLoose(0, rawDeliveryLimit());
                return;
            }
            ContainerTarget source = sources.get(index);
            CacheSnapshot snapshot = cacheSnapshots.get(source.position());
            Map<String, Integer> wanted = new LinkedHashMap<>();
            int slots = remainingBatchSlots();
            if (shareFullBox) slots = Math.min(slots, 1);
            for (ItemStack stack : snapshot.stacks()) {
                if (slots == 0) break;
                if (!MinecraftItemIdentity.isBox(stack)
                        || !category.equals(MinecraftItemIdentity.boxCategory(stack))
                        || (!policy.allowPartialBoxes() || existingBoxSpace > 0
                                || isRetainedPartialSource(source, partialSources))
                                && !MinecraftItemIdentity.full(stack)) continue;
                String key = MinecraftItemIdentity.key(stack);
                if (shareFullBox && !snapshot.fullBoxKeys().contains(key)) continue;
                int amount = Math.min(stack.getCount(), slots);
                wanted.merge(key, amount, Math::addExact);
                slots -= amount;
            }
            Map<String, Integer> before = shareFullBox ? owned() : Map.of();
            transfer(source, inventoryFitting(wanted, snapshot), true, () -> {
                Map<String, Integer> after = shareFullBox ? owned() : Map.of();
                if (shareFullBox && wanted.keySet().stream().anyMatch(key ->
                        after.getOrDefault(key, 0) > before.getOrDefault(key, 0))) {
                    finishCategoryLoad();
                } else {
                    gather(index + 1);
                }
            });
        }

        private void gatherLoose(int index, int wanted) {
            if (index >= sources.size() || sum(rawOwned()) >= wanted) {
                prepareLoosePayload(wanted);
                return;
            }
            ContainerTarget source = sources.get(index);
            CacheSnapshot snapshot = cacheSnapshots.get(source.position());
            var requested = raw(snapshot.stacks());
            var fitting = inventoryFitting(requested, snapshot);
            transfer(source, take(fitting, wanted - sum(rawOwned())), true,
                    () -> gatherLoose(index + 1, wanted));
        }

        private void finishCategoryLoad() {
            boolean cargo = owned().keySet().stream().anyMatch(key ->
                    !ItemIdentity.isEmptyBox(key) && category.equals(ItemIdentity.category(key)));
            if (!cargo) context.workspace().deferCacheCategory(category);
            int tail = Math.max(0, sum(rawOwned()) - looseDeficit - existingBoxSpace);
            if (tail > 0 && ownedEmptyBox(owned()).isEmpty() && cleanupAvailable()) {
                withdrawEmptyBox(0, ignored -> loadCompanionCategories());
            } else {
                loadCompanionCategories();
            }
        }

        private void loadCompanionCategories() {
            if (remainingBatchSlots() > 0
                    && minecraft.player.getInventory().getNonEquipmentItems()
                    .stream().filter(ItemStack::isEmpty).count() > RESERVED_INVENTORY_SLOTS) {
                Map<String, Set<ContainerTarget>> partialStock = companionPartialSources();
                Set<String> looseStock = companionLooseCategories();
                Set<String> candidates = new LinkedHashSet<>(partialStock.keySet());
                candidates.addAll(looseStock);
                Set<String> ordered = new LinkedHashSet<>(
                        context.workspace().classificationItemOrder(candidates));
                ordered.addAll(candidates);
                Map<String, List<ContainerTarget>> retainedByDestination = new LinkedHashMap<>();
                for (String next : ordered) {
                    List<ContainerTarget> targets = context.workspace().classificationTargets(next);
                    if (targets.isEmpty()) continue;
                    ContainerTarget target = targets.getFirst();
                    StoragePolicy nextPolicy = target.storagePolicy();
                    if (nextPolicy.mode() == StorageMode.NORMAL) continue;
                    List<ContainerTarget> retained = retainedByDestination.computeIfAbsent(
                            nextPolicy.partialBoxDestinationRef(),
                            ignored -> partialBoxContainers(context, nextPolicy, configuration));
                    if (!looseStock.contains(next) && partialStock.get(next).stream()
                            .allMatch(source -> isRetainedPartialSource(source, retained))) {
                        continue;
                    }
                    category = next;
                    destination = target;
                    policy = nextPolicy;
                    startCategory(retained);
                    return;
                }
            }
            gatherOtherCategories(0);
        }

        private boolean hasCompanionStock() {
            Set<String> remaining = context.workspace().remainingCacheCategories(initialCache);
            return cacheSnapshots.values().stream().flatMap(snapshot ->
                    snapshot.counts().keySet().stream()).filter(key -> !ItemIdentity.isEmptyBox(key))
                    .anyMatch(key -> isCompanionCategory(ItemIdentity.category(key), remaining));
        }

        private Set<String> companionLooseCategories() {
            Set<String> remaining = context.workspace().remainingCacheCategories(initialCache);
            Set<String> categories = new LinkedHashSet<>();
            for (CacheSnapshot snapshot : cacheSnapshots.values()) {
                snapshot.counts().keySet().stream().filter(key -> !ItemIdentity.isBox(key))
                        .map(ItemIdentity::category)
                        .filter(next -> isCompanionCategory(next, remaining))
                        .forEach(categories::add);
            }
            return categories;
        }

        private boolean isCompanionCategory(String next, Set<String> remaining) {
            return !preparedCategories.contains(next) && ItemIdentity.matches(next, remaining)
                    && !next.isEmpty() && !ItemIdentity.MIXED_BOX_CATEGORY.equals(next);
        }

        private boolean isRetainedPartialSource(ContainerTarget source,
                List<ContainerTarget> retained) {
            return retained.stream().anyMatch(container -> sameContainer(source, container));
        }

        private Map<String, Set<ContainerTarget>> companionPartialSources() {
            Set<String> remaining = context.workspace().remainingCacheCategories(initialCache);
            Map<String, Set<ContainerTarget>> partialStock = new LinkedHashMap<>();
            for (ContainerTarget source : sources) {
                for (ItemStack stack : cacheSnapshots.get(source.position()).stacks()) {
                    if (!MinecraftItemIdentity.isBox(stack) || MinecraftItemIdentity.full(stack)) {
                        continue;
                    }
                    String next = MinecraftItemIdentity.boxCategory(stack);
                    if (isCompanionCategory(next, remaining)) {
                        partialStock.computeIfAbsent(next, ignored -> new LinkedHashSet<>())
                                .add(source);
                    }
                }
            }
            return partialStock;
        }

        private Map<String, Integer> inventoryFitting(Map<String, Integer> wanted,
                CacheSnapshot snapshot) {
            if (wanted.isEmpty()) return Map.of();
            List<ItemStack> inventory = minecraft.player.getInventory().getNonEquipmentItems();
            List<ShulkerTransferPlanner.Slot> slots = inventory.stream()
                    .filter(stack -> !stack.isEmpty())
                    .map(stack -> new ShulkerTransferPlanner.Slot(MinecraftItemIdentity.key(stack),
                            stack.getCount(), stack.getMaxStackSize())).toList();
            return ShulkerTransferPlanner.fittingInventory(slots,
                    Math.max(0, inventory.size() - RESERVED_INVENTORY_SLOTS),
                    wanted, snapshot.stackSizes());
        }

        private void gatherOtherCategories(int index) {
            if (index >= sources.size() || remainingBatchSlots() == 0) {
                if (EmptyBoxSupplyPlan.canReturnUnusedBoxes(owned(),
                        inventoryStacks().stream().filter(MinecraftStorageAutomation::isEmptyBox)
                                .map(MinecraftItemIdentity::key)
                                .collect(java.util.stream.Collectors.toSet()))) {
                    returnEmptyBoxes(emptyBoxReturnsForSources(sources), () -> {});
                }
                return;
            }
            ContainerTarget source = sources.get(index);
            CacheSnapshot snapshot = cacheSnapshots.get(source.position());
            Set<String> present = snapshot.counts().keySet();
            Set<String> keys = context.workspace().mixedBoxWithdrawalItems(source, present).stream()
                    .filter(key -> !preparedCategories.contains(ItemIdentity.category(key)))
                    .filter(key -> CachePackingBatchPlan.canShareUnpreparedBatch(
                            context.workspace().classificationTargets(ItemIdentity.category(key))
                                    .stream().findFirst().map(target -> target.storagePolicy().mode())
                                    .orElse(StorageMode.BOX_ONLY),
                            snapshot.fullBoxKeys().contains(key)))
                    .collect(java.util.stream.Collectors.toSet());
            if (keys.isEmpty()) {
                gatherOtherCategories(index + 1);
                return;
            }
            var load = new ContainerBatchWithdrawal(context, source, keys, remainingBatchSlots());
            steps.add(new Step(load, () -> gatherOtherCategories(index + 1)));
        }

        private int targetLooseDeficit() {
            if (policy.mode() != StorageMode.MIXED) return 0;
            WarehouseInventory inventory = warehouse.get();
            WarehouseInventory.Container stock = inventory == null ? null
                    : inventory.containers().stream().filter(container -> container.recordedAt() != null
                            && container.dimension().equals(inventory.currentDimension())
                            && container.positions().stream().anyMatch(destination.entrances()::contains))
                    .findFirst().orElse(null);
            int slots = stock == null || stock.slots() <= 0 ? 54 : stock.slots();
            long present = stock == null ? 0 : stock.items().stream()
                    .filter(item -> category.equals(ItemIdentity.base(item.key())))
                    .mapToLong(WarehouseInventory.Entry::loose).sum();
            long otherSlots = stock == null ? 0 : stock.items().stream()
                    .filter(item -> !category.equals(ItemIdentity.base(item.key())) && item.loose() > 0)
                    .mapToLong(item -> (item.loose() + Math.max(1, item.stackSize()) - 1)
                            / Math.max(1, item.stackSize())).sum();
            return Math.toIntExact(Math.max(0,
                    StoragePackingPlanner.looseLimit(policy, slots, stackSize)
                    - present - otherSlots * stackSize));
        }

        private int recordedBoxSpace() {
            if (policy.mode() == StorageMode.BOX_ONLY && policy.partialBoxDestinationRef() != null) {
                return 0;
            }
            WarehouseInventory inventory = warehouse.get();
            if (inventory == null) return 0;
            List<WarehouseInventory.Box> boxes = new ArrayList<>();
            inventory.containers().stream().filter(container -> container.recordedAt() != null
                    && container.dimension().equals(inventory.currentDimension())
                    && container.positions().stream().anyMatch(destination.entrances()::contains)
                    && sources.stream().noneMatch(source -> container.positions().stream()
                            .anyMatch(source.entrances()::contains)))
                    .forEach(container -> boxes.addAll(container.boxes()));
            long space = boxes.stream().filter(box -> category.equals(box.category()))
                    .mapToLong(box -> Math.max(0, (long) StoragePackingPlanner.BOX_SLOTS * stackSize
                            - box.items().stream().mapToLong(WarehouseInventory.Entry::loose).sum()))
                    .sum();
            return Math.toIntExact(Math.min(Integer.MAX_VALUE, space));
        }

        @Override
        String completionMessage() {
            return "散货与未满盒已在缓存端合并打包，整批物品按仓储排版运输";
        }
    }

    private record CachedBox(ContainerTarget source, ItemStack box) {
    }

    private record CachePackingRound(CachedBox keeper, CachePackingBatchPlan plan,
            Map<String, Integer> load, Map<String, Integer> loose) {
    }

    private final class CacheBoxBatch extends Session {
        private final ContainerTarget cache;
        private List<ContainerTarget> sources;
        private int sourceCursor;
        private ContainerTarget currentSource;
        private ItemStack box;
        private ContainerBatchWithdrawal drain;
        private final Set<String> skippedBoxes = new java.util.HashSet<>();
        private final String packingCategory;
        private final ContainerTarget keeperSource;
        private final String keeperKey;
        private final int rawBudget;
        private final List<ContainerTarget> packingSources;
        private final Map<String, Integer> packingLoad;
        private final Map<String, Integer> loosePackingLoad = new LinkedHashMap<>();
        private Map<String, Integer> managedBefore;
        private int rawBefore;
        private int donorBefore;
        private boolean donorProgress;

        CacheBoxBatch(TaskExecutionContext context, ContainerTarget cache) {
            this(context, cache, null, null, "", Integer.MAX_VALUE, null, List.of(), Map.of(),
                    Map.of());
        }

        CacheBoxBatch(TaskExecutionContext context, ContainerTarget cache, String category,
                ContainerTarget keeperSource, String keeperKey, int rawBudget,
                Map<BlockPosition, CacheSnapshot> snapshots,
                List<ContainerTarget> packingSources, Map<String, Integer> packingLoad,
                Map<String, Integer> loosePackingStock) {
            super(context, snapshots);
            this.cache = cache;
            this.packingCategory = category;
            this.keeperSource = keeperSource;
            this.keeperKey = keeperKey;
            this.rawBudget = rawBudget;
            this.packingSources = List.copyOf(packingSources);
            this.packingLoad = Map.copyOf(packingLoad);
            packingLoad.forEach((key, count) -> {
                int loose = Math.min(count, loosePackingStock.getOrDefault(key, 0));
                if (loose > 0) {
                    loosePackingLoad.put(key, loose);
                }
            });
        }

        @Override
        void initialize() {
            rawBefore = packingRawCount();
            managedBefore = owned();
            List<ContainerTarget> candidates = new ArrayList<>();
            candidates.add(cache);
            if (!context.workspace().unstackableClassification()) {
                candidates.addAll(context.workspace().cacheContainers());
            }
            candidates.addAll(packingSources);
            sources = EmptyBoxSupplyPlan.sources(candidates, null, List.of());
            loadPackingLoose(0);
        }

        private void loadPackingLoose(int index) {
            for (int cursor = index; cursor < sources.size() && !loosePackingLoad.isEmpty()
                    && budgetLeft() > 0 && batchSlots() > 0; cursor++) {
                ContainerTarget source = sources.get(cursor);
                List<ItemStack> contents = cacheSnapshots.getOrDefault(source.position(),
                        CacheSnapshot.EMPTY).stacks();
                Map<String, Integer> requested = packingRequest(contents, loosePackingLoad);
                if (requested.isEmpty()) continue;
                int next = cursor + 1;
                transfer(source, requested, true, () -> loadPackingLoose(next));
                return;
            }
            visitNext();
        }

        private void visitNext() {
            while (sourceCursor < sources.size() && batchSlots() > 0 && budgetLeft() > 0) {
                currentSource = sources.get(sourceCursor++);
                if (queueSourceWork()) return;
            }
        }

        private void inspectSource() {
            if (batchSlots() > 0 && budgetLeft() > 0 && !queueSourceWork()) visitNext();
        }

        private boolean queueSourceWork() {
            Optional<List<ItemStack>> confirmed = confirmedCacheContents(context, currentSource);
            if (confirmed.isPresent()) return prepareSource(confirmed.orElseThrow());
            readCache(currentSource, contents -> {
                if (!prepareSource(contents)) visitNext();
            });
            return true;
        }

        private boolean prepareSource(List<ItemStack> contents) {
            box = contents.stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                    && !isEmptyBox(stack) && shouldUnpack(stack))
                    .filter(stack -> packingLoad.isEmpty() || keeperSource != null
                            || ItemIdentity.isMixedBox(MinecraftItemIdentity.key(stack)))
                    .filter(stack -> keeperSource == null
                            || !sameContainer(currentSource, keeperSource)
                            || !MinecraftItemIdentity.key(stack).equals(keeperKey)
                            || contents.stream().filter(other -> !other.isEmpty()
                                    && MinecraftItemIdentity.key(other).equals(keeperKey)).count() > 1)
                    .filter(stack -> !skippedBoxes.contains(
                            currentSource.position() + ":" + MinecraftItemIdentity.key(stack)))
                    .filter(stack -> !eligibleItems(stack).isEmpty())
                    .filter(stack -> packingCategory == null || !packingRequest(
                            MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream()
                                    .toList()).isEmpty())
                    .min(Comparator.comparingInt(this::layoutPriority))
                    .map(ItemStack::copy).orElse(ItemStack.EMPTY);
            if (box.isEmpty()) {
                if (packingCategory != null) return false;
                Set<String> keys = contents.stream().filter(stack -> !stack.isEmpty()
                        && !MinecraftItemIdentity.isBox(stack))
                        .map(MinecraftItemIdentity::key).collect(java.util.stream.Collectors.toSet());
                keys = context.workspace().mixedBoxWithdrawalItems(currentSource, keys);
                if (keys.isEmpty()) return false;
                var loose = new ContainerBatchWithdrawal(context, currentSource, selectors(keys),
                        remainingBatchSlots());
                steps.add(new Step(loose, this::visitNext));
                return true;
            }
            if (!cleanupAvailable()) {
                throw new IllegalStateException("拆盒分类前请配置任务中断存放位置");
            }
            if (hasTemporaryResources(owner())) {
                throw new IllegalStateException("上次潜影盒尚未回收，请先执行全局收尾");
            }
            BlockPosition site = chooseSite(context, currentSource, null, configuration);
            String key = MinecraftItemIdentity.key(box);
            Set<String> innerKeys = eligibleItems(box);
            int before = owned().getOrDefault(key, 0);
            transfer(currentSource, Map.of(key, 1), true, () -> {
                if (owned().getOrDefault(key, 0) != before + 1) {
                    throw new IllegalStateException("缓存潜影盒取出未确认，已停止拆盒");
                }
                var resource = new MinecraftShulkerAction.Resource(site, box);
                resources.put(owner(), resource);
                if (packingCategory != null) {
                    donorBefore = packingRawCount();
                    donorProgress = false;
                    steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource,
                            true), () -> {
                                var source = new ContainerTarget(site, "minecraft:shulker_box");
                                Map<String, Integer> requested = packingRequest(
                                        MinecraftItemIdentity.contents(box)
                                                .nonEmptyItemCopyStream()
                                                .toList());
                                transfer(source, requested, true, () -> {
                                    donorProgress = packingRawCount() > donorBefore;
                                    recoverBox();
                                });
                            }));
                    return;
                }
                drain = new ContainerBatchWithdrawal(context,
                        new ContainerTarget(site, "minecraft:shulker_box"), selectors(innerKeys),
                        Math.min(StoragePackingPlanner.BOX_SLOTS, remainingBatchSlots() + 1));
                steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource,
                        true), () -> steps.add(new Step(drain, this::recoverBox))));
            });
            return true;
        }

        private Set<String> eligibleItems(ItemStack candidate) {
            Set<String> contents = MinecraftItemIdentity.contents(candidate).nonEmptyItemCopyStream()
                    .map(MinecraftItemIdentity::key).collect(java.util.stream.Collectors.toSet());
            Set<String> selected = context.workspace()
                    .mixedBoxWithdrawalItems(currentSource, contents);
            return packingCategory == null ? selected : selected.stream()
                    .filter(key -> ItemIdentity.category(key).equals(packingCategory))
                    .collect(java.util.stream.Collectors.toSet());
        }

        private Set<String> selectors(Set<String> keys) {
            return keys.stream()
                    .map(key -> context.workspace().hasExactClassification(key)
                            ? ItemIdentity.exact(key) : key)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        private boolean shouldUnpack(ItemStack candidate) {
            if (context.workspace().unstackableClassification()) return true;
            if (packingCategory == null && MinecraftItemIdentity.contents(candidate)
                    .nonEmptyItemCopyStream().map(MinecraftItemIdentity::key)
                    .anyMatch(context.workspace()::hasExactClassification)) return true;
            if (packingCategory != null) {
                return ItemIdentity.isMixedBox(MinecraftItemIdentity.key(candidate))
                        || !MinecraftItemIdentity.full(candidate)
                                && packingCategory.equals(MinecraftItemIdentity.boxCategory(candidate));
            }
            boolean mixed = ItemIdentity.isMixedBox(MinecraftItemIdentity.key(candidate));
            if (mixed) return true;
            String category = MinecraftItemIdentity.boxCategory(candidate);
            List<ContainerTarget> targets = context.workspace().classificationTargets(category);
            if (!targets.isEmpty()) {
                StoragePolicy policy = targets.getFirst().storagePolicy();
                // Let the category's packing plan decide if its retained partials need merging.
                if (policy.mode() == StorageMode.BOX_ONLY
                        && policy.partialBoxDestinationRef() != null) return false;
            }
            return context.workspace().acceptedItemsByContainer().entrySet().stream()
                    .filter(entry -> entry.getValue().contains(category))
                    .anyMatch(entry -> StoragePackingPlanner.unpackIncomingBox(false,
                            MinecraftItemIdentity.full(candidate), entry.getKey().storagePolicy()));
        }

        private int layoutPriority(ItemStack candidate) {
            List<String> order = context.workspace().classificationItemOrder(
                    context.workspace().remainingCacheCategories(currentSource));
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
                if (packingCategory == null ? drain.withdrawn().isEmpty() : !donorProgress) {
                    skippedBoxes.add(currentSource.position() + ":"
                            + MinecraftItemIdentity.key(box));
                }
                if (isEmptyBox(box)) {
                    long emptyCount = inventoryStacks().stream()
                            .filter(MinecraftStorageAutomation::isEmptyBox)
                            .mapToInt(stack -> Math.min(stack.getCount(), owned().getOrDefault(
                                    MinecraftItemIdentity.key(stack), 0))).sum();
                    boolean packingNeeded = context.workspace().acceptedItemsByContainer()
                            .entrySet().stream().anyMatch(entry ->
                                    entry.getKey().storagePolicy().mode() != StorageMode.NORMAL
                                            && owned().keySet().stream().anyMatch(key ->
                                                    !ItemIdentity.isBox(key)
                                                            && ItemIdentity.matches(key,
                                                                    entry.getValue())));
                    if (emptyCount <= 1 && packingNeeded) {
                        inspectSource();
                    } else {
                        storeEmptyBox(box, emptyBoxReturns(currentSource), 0, this::inspectSource);
                    }
                } else {
                    returnBox(0);
                }
            });
        }

        private void returnBox(int index) {
            List<ContainerTarget> destinations = List.of(currentSource);
            if (index >= destinations.size()) {
                throw new IllegalStateException("拆盒后的潜影盒无法归还，交给全局收尾");
            }
            String key = MinecraftItemIdentity.key(box);
            int before = owned().getOrDefault(key, 0);
            ContainerTarget destination = destinations.get(index);
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    destination, Map.of(key, 1), false, false);
            Runnable after = () -> {
                if (owned().getOrDefault(key, 0) == before - 1) {
                    inspectSource();
                } else {
                    returnBox(index + 1);
                }
            };
            steps.add(new Step(access, after, after));
        }

        private int packingRawCount() {
            return owned().entrySet().stream().filter(entry -> !ItemIdentity.isBox(entry.getKey())
                    && (packingCategory == null || ItemIdentity.category(entry.getKey())
                            .equals(packingCategory))).mapToInt(Map.Entry::getValue).sum();
        }

        private int budgetLeft() {
            return Math.max(0, rawBudget - Math.max(0, packingRawCount() - rawBefore));
        }

        private int batchSlots() {
            if (packingCategory == null) return remainingBatchSlots();
            List<ItemStack> inventory = minecraft.player.getInventory().getNonEquipmentItems();
            int free = inventory.size() - 2
                    - (int) inventory.stream().filter(stack -> !stack.isEmpty()).count();
            boolean slack = inventory.stream().anyMatch(stack -> !stack.isEmpty()
                    && packingCategory.equals(MinecraftItemIdentity.base(stack))
                    && stack.getCount() < stack.getMaxStackSize());
            return Math.max(slack ? 1 : 0, free);
        }

        private Map<String, Integer> packingRequest(List<ItemStack> contents) {
            return packingRequest(contents, packingLoad);
        }

        private Map<String, Integer> packingRequest(List<ItemStack> contents,
                Map<String, Integer> limits) {
            Map<String, Integer> items = new LinkedHashMap<>();
            Map<String, Integer> sizes = new LinkedHashMap<>(inventoryStackSizes());
            for (ItemStack stack : contents) {
                if (stack.isEmpty() || MinecraftItemIdentity.isBox(stack)
                        || !packingCategory.equals(MinecraftItemIdentity.base(stack))) continue;
                String key = MinecraftItemIdentity.key(stack);
                items.merge(key, stack.getCount(), Math::addExact);
                sizes.put(key, stack.getMaxStackSize());
            }
            if (!limits.isEmpty()) {
                Map<String, Integer> managed = owned();
                items.replaceAll((key, count) -> {
                    int loaded = Math.max(0,
                            managed.getOrDefault(key, 0) - managedBefore.getOrDefault(key, 0));
                    return Math.min(count, limits.getOrDefault(key, 0) - loaded);
                });
                items.values().removeIf(count -> count <= 0);
            }
            List<ItemStack> inventory = minecraft.player.getInventory().getNonEquipmentItems();
            List<ShulkerTransferPlanner.Slot> slots = transferSlots(inventory);
            int capacity = Math.min(inventory.size() - 2, slots.size() + batchSlots());
            return take(ShulkerTransferPlanner.fitting(slots, Math.max(0, capacity), items, sizes),
                    budgetLeft());
        }

        @Override
        String completionMessage() {
            return "缓存中的未满盒与混杂盒已合并为分类批次，放置的潜影盒已回收";
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
        private final boolean cacheStaging;
        private final ItemStack plannedKeeper;

        Packing(TaskExecutionContext context, ContainerTarget target, Map<String, Integer> items) {
            this(context, target, items, ItemStack.EMPTY, null);
        }

        Packing(TaskExecutionContext context, ContainerTarget target, Map<String, Integer> items,
                ItemStack plannedKeeper, Map<BlockPosition, CacheSnapshot> snapshots) {
            super(context, snapshots);
            this.cacheStaging = snapshots != null;
            this.plannedKeeper = plannedKeeper;
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
            List<ContainerTarget> configuredPartials =
                    partialBoxContainers(context, policy, configuration);
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
            } else if (!cacheStaging && context.workspace().isClassificationStorage(target)
                    && EmptyBoxSupplyPlan.canReturnUnusedBoxes(owned(), inventoryStacks().stream()
                            .filter(MinecraftStorageAutomation::isEmptyBox)
                            .map(MinecraftItemIdentity::key)
                            .collect(java.util.stream.Collectors.toSet()))) {
                returnEmptyBoxes(() -> {});
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
            Map<String, Integer> full = fullBoxes(carriedBoxes);
            if (!full.isEmpty()) {
                transferFullBoxes(full, ignored -> packBoxesOnly());
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
            Map<String, Integer> full = fullBoxes(boxes);
            if (!full.isEmpty()) {
                transferFullBoxes(full, this::inspectContents);
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
            if (cacheStaging) {
                box = plannedKeeper;
                boxSource = target;
                requestedBox = false;
                continueBoxChoice();
                return;
            }
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

        private Map<String, Integer> fullBoxes(List<ItemStack> boxes) {
            Map<String, Integer> result = new LinkedHashMap<>();
            boxes.stream().filter(MinecraftItemIdentity::full).forEach(stack -> {
                String key = MinecraftItemIdentity.key(stack);
                result.merge(key, stack.getCount(), Math::addExact);
            });
            result.replaceAll((key, count) -> Math.min(count, owned().getOrDefault(key, 0)));
            result.values().removeIf(count -> count <= 0);
            return result;
        }

        private void transferFullBoxes(Map<String, Integer> boxes, Consumer<List<ItemStack>> after) {
            int before = boxes.keySet().stream().mapToInt(key -> owned().getOrDefault(key, 0)).sum();
            transfer(target, boxes, false, updated -> {
                int left = boxes.keySet().stream().mapToInt(key -> owned().getOrDefault(key, 0)).sum();
                if (left >= before) {
                    throw new IllegalStateException("目标容器无法接收本批满盒，尝试同类下一箱");
                }
                after.accept(updated);
            });
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
                Map<String, Integer> carried = rawOwned();
                Map<String, Integer> incoming = cacheStaging
                        ? ShulkerTransferPlanner.fitting(transferSlots(contents), contents.size(),
                                carried, inventoryStackSizes())
                        : take(carried, remaining);
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
            List<ContainerTarget> fallbacks = cacheStaging
                    ? context.workspace().cacheContainers() : List.of();
            storePackedBox(target, box, fallbacks, updated -> {
                if (cacheStaging) {
                    finishCategory();
                    return;
                }
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
            partialSources = partialBoxContainers(context, target.storagePolicy(), configuration)
                    .stream()
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
            if (isEmptyBox(box)) {
                if (madeProgress) read(target, this::inspect);
                return;
            }
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
        private final RegionDefinition cacheRegion;
        private List<ContainerTarget> cacheSources = List.of();
        private List<ContainerTarget> destinations;
        private RegionDefinition unstackableRegion;
        private List<ContainerTarget> unstackableDestinations;
        private final Set<BlockPosition> reservedPositions = new LinkedHashSet<>();
        private int storedUnstackable;
        private boolean unstackableFull;
        private boolean storedOrdinary;
        private ContainerVisitPlan sources;
        private ContainerTarget currentSource;
        private ContainerTarget boxTarget;
        private ItemStack box;
        private ContainerTarget reusedFrom;
        private final Set<String> unusableBoxes = new java.util.HashSet<>();
        private final Set<String> undrainableBoxes = new java.util.HashSet<>();

        Overflow(TaskExecutionContext context, RegionDefinition cacheRegion) {
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
            unstackableRegion = settings.unstackableOverflowRegion();
            if (unstackableRegion != null) {
                reservedPositions.addAll(configuration.emptyBoxContainers());
                destinations.forEach(target -> reservedPositions.addAll(target.entrances()));
                context.workspace().containerScan().stream()
                        .flatMap(scan -> scan.containers().stream())
                        .forEach(target -> reservedPositions.addAll(target.entrances()));
            }
            if (hasTemporaryResources(owner())) {
                throw new IllegalStateException("尚有未回收潜影盒，请先执行全局收尾");
            }
            List<ContainerTarget> caches = List.of();
            if (cacheRegion != null) {
                var scan = containerRegions.scanComplete(cacheRegion, Set.of(), 1_000_000);
                if (!scan.succeeded()) throw new IllegalStateException(scan.message());
                if (unstackableRegion != null) {
                    scan.containers().forEach(target ->
                            reservedPositions.addAll(target.entrances()));
                }
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
            cacheSources = caches;
            packOwned();
        }

        private void packOwned() {
            if (!rawOwned().isEmpty()) {
                Map<String, Integer> items = unstackableOwned();
                if (items.isEmpty()) {
                    findReusableOverflowBox(0);
                } else {
                    storeUnstackable(0, items);
                }
                return;
            }
            ItemStack filled = inventoryStacks().stream().filter(MinecraftItemIdentity::isBox)
                    .filter(stack -> !isEmptyBox(stack))
                    .filter(stack -> owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                    .findFirst().orElse(ItemStack.EMPTY);
            if (!filled.isEmpty()) {
                box = filled;
                reusedFrom = null;
                Set<String> unstackable = unstackableRegion == null ? Set.of()
                        : MinecraftItemIdentity.contents(box).nonEmptyItemCopyStream()
                                .filter(MinecraftStorageAutomation::isUnstackableItem)
                                .map(MinecraftItemIdentity::key).map(ItemIdentity::exact)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (!unstackable.isEmpty()) {
                    unpackOverflowBox(unstackable, true);
                } else if (MinecraftItemIdentity.full(box)
                        || undrainableBoxes.contains(MinecraftItemIdentity.key(box))) {
                    storeOverflowBox(0);
                } else {
                    Set<String> contents = MinecraftItemIdentity.contents(box)
                            .nonEmptyItemCopyStream().map(MinecraftItemIdentity::key)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    unpackOverflowBox(contents, false);
                }
                return;
            }
            if (currentSource != null) {
                drainSource();
            } else {
                visitSource();
            }
        }

        private Map<String, Integer> unstackableOwned() {
            if (unstackableRegion == null) return Map.of();
            Map<String, Integer> managed = owned();
            Map<String, Integer> items = new LinkedHashMap<>();
            for (ItemStack stack : inventoryStacks()) {
                if (!isUnstackableItem(stack)) continue;
                String key = MinecraftItemIdentity.key(stack);
                int count = managed.getOrDefault(key, 0);
                if (count > 0) items.put(key, count);
            }
            return items;
        }

        private List<ContainerTarget> unstackableDestinations() {
            if (unstackableDestinations != null) return unstackableDestinations;
            var scan = containerRegions.scanComplete(unstackableRegion,
                    TemporaryContainerResolver.TYPES, 1_000_000);
            if (!scan.succeeded()) {
                throw new IllegalStateException("不可堆叠溢出区域扫描失败：" + scan.message());
            }
            if (scan.containers().isEmpty()) {
                throw new IllegalStateException("不可堆叠溢出区域没有箱子或木桶");
            }
            if (scan.containers().stream().flatMap(target -> target.entrances().stream())
                    .anyMatch(reservedPositions::contains)) {
                throw new IllegalStateException("不可堆叠溢出与缓存、普通溢出或空盒供应共用容器");
            }
            unstackableDestinations = scan.containers();
            return unstackableDestinations;
        }

        private void storeUnstackable(int index, Map<String, Integer> items) {
            List<ContainerTarget> targets = unstackableDestinations();
            if (index >= targets.size()) {
                unstackableFull = true;
                findReusableOverflowBox(0);
                return;
            }
            ContainerTarget target = targets.get(index);
            transfer(target, items, false, contents -> {
                Map<String, Integer> remaining = unstackableOwned();
                storedUnstackable += sum(items) - sum(remaining);
                if (remaining.isEmpty()) {
                    packOwned();
                } else if (!contents.isEmpty() && contents.stream().noneMatch(ItemStack::isEmpty)) {
                    storeUnstackable(index + 1, remaining);
                } else {
                    throw new IllegalStateException("不可堆叠溢出箱有空位但未能完成存放："
                            + target.position());
                }
            });
        }

        private void findReusableOverflowBox(int index) {
            if (index >= destinations.size()) {
                useOwnedOrEmptyBox();
                return;
            }
            ContainerTarget destination = destinations.get(index);
            read(destination, contents -> {
                ItemStack reusable = contents.stream().filter(MinecraftItemIdentity::isBox)
                        .filter(stack -> !unusableBoxes.contains(destination.position() + ":"
                                + MinecraftItemIdentity.key(stack)))
                        .filter(stack -> !fittingBoxItems(stack, rawOwned()).isEmpty())
                        .max(Comparator.<ItemStack>comparingInt(stack ->
                                sum(fittingBoxItems(stack, rawOwned())))
                                .thenComparingInt(MinecraftStorageAutomation::insideCount))
                        .map(ItemStack::copy).orElse(ItemStack.EMPTY);
                if (reusable.isEmpty()) {
                    findReusableOverflowBox(index + 1);
                    return;
                }
                String key = MinecraftItemIdentity.key(reusable);
                int before = owned().getOrDefault(key, 0);
                var access = new MinecraftStorageAccess(minecraft, context, destination,
                        Map.of(key, 1), true, false);
                Runnable acquired = () -> {
                    if (owned().getOrDefault(key, 0) == before + 1) {
                        box = reusable;
                        reusedFrom = destination;
                        placeOverflowBox();
                    } else {
                        unusableBoxes.add(destination.position() + ":" + key);
                        findReusableOverflowBox(index + 1);
                    }
                };
                steps.add(new Step(access, acquired, acquired));
            }, () -> findReusableOverflowBox(index + 1));
        }

        private void useOwnedOrEmptyBox() {
            reusedFrom = null;
            box = inventoryStacks().stream().filter(MinecraftItemIdentity::isBox)
                    .filter(stack -> owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                    .filter(stack -> !fittingBoxItems(stack, rawOwned()).isEmpty())
                    .max(Comparator.comparingInt(MinecraftStorageAutomation::insideCount))
                    .map(ItemStack::copy).orElse(ItemStack.EMPTY);
            if (!box.isEmpty()) {
                placeOverflowBox();
            } else {
                withdrawEmptyBox(0, supplied -> {
                    box = supplied;
                    placeOverflowBox();
                });
            }
        }

        private void unpackOverflowBox(Set<String> items, boolean requireItems) {
            BlockPosition site = chooseSite(context,
                    currentSource == null ? destinations.getFirst() : currentSource,
                    null, configuration);
            var resource = new MinecraftShulkerAction.Resource(site, box);
            resources.put(owner(), resource);
            var drain = new ContainerBatchWithdrawal(context,
                    new ContainerTarget(site, "minecraft:shulker_box"), items,
                    Math.min(StoragePackingPlanner.BOX_SLOTS, remainingBatchSlots() + 1));
            steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, true),
                    () -> steps.add(new Step(drain, () -> recover(() -> {
                        box = resource.expected;
                        if (drain.withdrawn().isEmpty()) {
                            if (requireItems) {
                                throw new IllegalStateException("未能取出潜影盒中的不可堆叠物品");
                            }
                            undrainableBoxes.add(MinecraftItemIdentity.key(box));
                        }
                        packOwned();
                    })))));
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
            BlockPosition site = chooseSite(context,
                    reusedFrom == null ? destinations.getFirst() : reusedFrom, null, configuration);
            var resource = new MinecraftShulkerAction.Resource(site, box);
            resources.put(owner(), resource);
            boxTarget = new ContainerTarget(site, "minecraft:shulker_box");
            steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, true),
                    this::fillOverflowBox));
        }

        private void fillOverflowBox() {
            read(boxTarget, contents -> {
                List<ShulkerTransferPlanner.Slot> slots = contents.stream()
                        .filter(stack -> !stack.isEmpty()).map(stack ->
                                new ShulkerTransferPlanner.Slot(MinecraftItemIdentity.key(stack),
                                        stack.getCount(), stack.getMaxStackSize())).toList();
                Map<String, Integer> requested = ShulkerTransferPlanner.fitting(slots,
                        contents.size(), rawOwned(), inventoryStackSizes());
                int before = sum(rawOwned());
                transfer(boxTarget, requested, false, () -> {
                    boolean progressed = sum(rawOwned()) < before;
                    var resource = resources.get(owner());
                    recover(() -> {
                        box = resource.expected;
                        if (!progressed) {
                            if (reusedFrom == null) {
                                throw new IllegalStateException("溢出盒未确认接收物品，等待收尾");
                            }
                            unusableBoxes.add(reusedFrom.position() + ":"
                                    + MinecraftItemIdentity.key(box));
                        }
                        storeOverflowBox(0);
                    });
                });
            });
        }

        private void storeOverflowBox(int index) {
            List<ContainerTarget> returns = new ArrayList<>();
            if (reusedFrom != null) returns.add(reusedFrom);
            returns.addAll(destinations);
            returns = EmptyBoxSupplyPlan.sources(returns, null, List.of());
            if (index >= returns.size()) {
                throw new IllegalStateException("溢出设施已满或不可达，打包物品已保留，等待收尾");
            }
            String key = MinecraftItemIdentity.key(box);
            int before = owned().getOrDefault(key, 0);
            var access = new MinecraftStorageAccess(minecraft, context, returns.get(index),
                    Map.of(key, 1), false, false);
            Runnable after = () -> {
                if (owned().getOrDefault(key, 0) == before - 1) {
                    storedOrdinary = true;
                    reusedFrom = null;
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
            else returnEmptyBoxes(emptyBoxReturnsForSources(cacheSources), () -> {});
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
            if (unstackableFull && storedUnstackable == 0) {
                return "不可堆叠溢出区已满，剩余物品已打包送往普通溢出";
            }
            if (storedUnstackable > 0) {
                String stored = "已送往不可堆叠溢出区 " + storedUnstackable + " 件";
                return storedOrdinary ? stored + "，其余已打包送往普通溢出" : stored;
            }
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
            recover(() -> steps.add(new Step(new TaskModuleExecutor() {
                private dev.stowling.automation.PlayerInventorySnapshot previous;
                private int stable;

                @Override
                public TaskModuleResult start() {
                    return tick();
                }

                @Override
                public TaskModuleResult tick() {
                    var observed = context.inventory().observe();
                    if (observed.isEmpty()) {
                        return TaskModuleResult.failed("无法读取真实背包，收尾核对已停止");
                    }
                    var current = observed.orElseThrow().snapshot();
                    stable = current.equals(previous) ? stable + 1 : 0;
                    previous = current;
                    if (stable < 4) return TaskModuleResult.waiting("等待背包稳定，核对实际收尾物品");
                    context.inventory().reconcileMissingItems(
                            context.workspace().inventoryAccount());
                    return TaskModuleResult.succeeded("已核对实际收尾物品");
                }

                @Override
                public void cancel() {
                }
            }, this::resolveDestinations)));
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
            context.inventory().claimForCleanup(context.workspace().inventoryAccount());
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
            ContainerTarget target = targets.get(index);
            Map<String, Integer> items = owned();
            if (target.entrances().stream()
                    .anyMatch(context.workspace().partialBoxStoragePositions()::contains)
                    && items.keySet().stream().anyMatch(ItemIdentity::isEmptyBox)) {
                returnEmptyBoxes(() -> depositAll(index));
                return;
            }
            MinecraftStorageAccess access = new MinecraftStorageAccess(minecraft, context,
                    target, items, false, false);
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
                .orElseThrow(() -> new IllegalStateException("附近没有可用打包位置，请指定单片打包区域"
                        + "；目标=" + target.position() + "；打包区域=" + allowed
                        + "。需要空置的放盒和开盖空间，且下方为不可右键交互的坚固方块"));
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

    private Map<String, Integer> inventoryStackSizes() {
        Map<String, Integer> sizes = new LinkedHashMap<>();
        inventoryStacks().forEach(stack -> sizes.put(MinecraftItemIdentity.key(stack),
                stack.getMaxStackSize()));
        return sizes;
    }

    private Map<String, Integer> fittingBoxItems(ItemStack box, Map<String, Integer> incoming) {
        return ShulkerTransferPlanner.fitting(boxSlots(box), StoragePackingPlanner.BOX_SLOTS,
                incoming, inventoryStackSizes());
    }

    private static List<ShulkerTransferPlanner.Slot> boxSlots(ItemStack box) {
        return transferSlots(MinecraftItemIdentity.contents(box).nonEmptyItemCopyStream().toList());
    }

    private static List<ShulkerTransferPlanner.Slot> transferSlots(List<ItemStack> contents) {
        return contents.stream().filter(stack -> !stack.isEmpty())
                .map(stack -> new ShulkerTransferPlanner.Slot(
                        MinecraftItemIdentity.key(stack), stack.getCount(), stack.getMaxStackSize()))
                .toList();
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
