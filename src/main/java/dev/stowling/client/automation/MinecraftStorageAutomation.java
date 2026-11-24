package dev.stowling.client.automation;

import dev.stowling.automation.ClassificationOverflowCapacity;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerVisitPlan;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.EmptyBoxSupplyPlan;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.automation.ShulkerTransferPlanner;
import dev.stowling.automation.TemporaryContainerResolver;
import dev.stowling.automation.CachePackingBatchPlan;
import dev.stowling.automation.PartialBoxSourcePlan;
import dev.stowling.automation.RobotWarehousePackingPlan;
import dev.stowling.domain.BlockPosition;
import dev.stowling.inventory.RecordedStorageCapacity;
import dev.stowling.inventory.WarehouseInventory;
import dev.stowling.v2.cache.RobotWarehouseCache;
import dev.stowling.v2.cache.RobotWarehouseCache.Location;
import dev.stowling.v2.config.StorageSettingsStore;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.ContainerCacheRefresh;
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
import dev.stowling.v2.runtime.modules.CacheLocations;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.AABB;

/** Shared packing and cleanup coordinator. One active task owns the client's interaction slot. */
public final class MinecraftStorageAutomation implements StorageAutomationPort {
    private final Minecraft minecraft;
    private final StorageSettingsStore settings;
    private final dev.stowling.automation.ContainerRegionPort containerRegions;
    private final Supplier<WarehouseInventory> warehouse;
    private final Supplier<WarehouseInventory> materialWarehouse;
    private final RobotWarehouseCache robotWarehouse;
    private final Map<UUID, MinecraftShulkerAction.Resource> resources = new LinkedHashMap<>();
    private record IntakeStash(ContainerTarget supply, Map<String, Integer> items) {
    }

    private final Map<UUID, IntakeStash> intakeStashes = new LinkedHashMap<>();
    private final Map<TaskRunWorkspace, Map<BlockPosition, CacheSnapshot>> cacheSnapshotsByRun =
            new WeakHashMap<>();
    private final Map<TaskRunWorkspace, List<ContainerTarget>> boxWarehouses = new WeakHashMap<>();

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
        robotWarehouse = new RobotWarehouseCache(FabricLoader.getInstance().getConfigDir()
                .resolve("stowling-robot-warehouse-cache.json"));
    }

    @Override
    public boolean cleanupEnabled() {
        return settings.resolved().cleanupEnabled();
    }

    @Override
    public boolean hasTemporaryResources(UUID owner) {
        return resources.containsKey(owner) || intakeStashes.containsKey(owner);
    }

    @Override
    public void releaseTemporaryResources(UUID owner) {
        resources.remove(owner);
        intakeStashes.remove(owner);
    }

    @Override
    public void adoptTemporaryResources(UUID previousOwner, UUID nextOwner) {
        if (hasTemporaryResources(nextOwner)) {
            throw new IllegalStateException("接收分类收尾前仍有未处理的缓存打包资源");
        }
        var resource = resources.remove(previousOwner);
        if (resource != null) resources.put(nextOwner, resource);
        var stash = intakeStashes.remove(previousOwner);
        if (stash != null) intakeStashes.put(nextOwner, stash);
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
        var parameters = step.parameters();
        return new MaterialCollection(context,
                ResourceReference.parse(parameters.get("targetRef")),
                parameters.get(MaterialRequirements.SOURCE_ZONE_REF),
                MaterialRequirements.forCollection(parameters),
                Boolean.parseBoolean(parameters.get(MaterialRequirements.BOXED_DELIVERY)),
                Boolean.parseBoolean(parameters.get(MaterialRequirements.SINGLE_MATERIAL_BOXES)));
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
    public TaskModuleExecutor storeCacheIntake(TaskExecutionContext context,
            List<ContainerTarget> containers, boolean pack) {
        return new CacheIntake(context, containers, pack);
    }

    @Override
    public TaskModuleExecutor cleanup(TaskExecutionContext context, boolean overflowEnabled) {
        return new Cleanup(context, overflowEnabled);
    }

    @Override
    public TaskModuleExecutor overflow(TaskExecutionContext context, RegionDefinition cacheRegion) {
        return new Overflow(context, cacheRegion == null ? null
                : ResourceReference.of(ResourceType.REGION, cacheRegion.id()), false, null);
    }

    @Override
    public TaskModuleExecutor robotWarehouse(TaskExecutionContext context,
            RegionDefinition cacheRegion) {
        return robotWarehouse(context, cacheRegion, null);
    }

    @Override
    public TaskModuleExecutor robotWarehouse(TaskExecutionContext context,
            RegionDefinition cacheRegion, ResourceReference destination) {
        return robotWarehouseForCache(context, cacheRegion == null ? null
                : ResourceReference.of(ResourceType.REGION, cacheRegion.id()), destination);
    }

    @Override
    public TaskModuleExecutor robotWarehouseForCache(TaskExecutionContext context,
            ResourceReference cache, ResourceReference destination) {
        return robotWarehouseForCache(context, cache, destination, true);
    }

    @Override
    public TaskModuleExecutor robotWarehouseForCache(TaskExecutionContext context,
            ResourceReference cache, ResourceReference destination, boolean mixedBoxes) {
        return new Overflow(context, cache, true, destination, null, mixedBoxes);
    }

    @Override
    public TaskModuleExecutor routeClassificationRemainders(TaskExecutionContext context,
            ResourceReference cache, ResourceReference destination, String kind) {
        return new Overflow(context, cache, false, destination, kind);
    }

    private String robotWarehouseScope() {
        if (minecraft.level == null || minecraft.player == null) {
            throw new IllegalStateException("进入世界后才能使用机器人仓库");
        }
        String scope = MinecraftWorldScope.identify(minecraft);
        if (scope == null) {
            throw new IllegalStateException("无法识别当前世界的机器人仓库缓存");
        }
        return scope;
    }

    private List<ContainerTarget> emptyBoxSources(TaskExecutionContext context) {
        var policy = context.workspace().cleanupPolicy().emptyBoxWarehouse();
        if (policy != null) {
            List<ContainerTarget> sources = new ArrayList<>(classificationCaches(context));
            sources.addAll(emptyBoxWarehouse(context));
            return EmptyBoxSupplyPlan.sources(sources,
                    context.workspace().selectedContainer().orElse(null), List.of());
        }
        List<ContainerTarget> caches = new ArrayList<>();
        context.workspace().containerScan().ifPresent(scan -> caches.addAll(scan.containers()));
        caches.addAll(context.workspace().cacheContainers());
        return EmptyBoxSupplyPlan.sources(caches,
                context.workspace().selectedContainer().orElse(null),
                taskStorage(context).emptyBoxContainers());
    }

    private List<ContainerTarget> classificationCaches(TaskExecutionContext context) {
        List<ContainerTarget> caches = new ArrayList<>();
        context.workspace().containerScan().ifPresent(scan -> caches.addAll(scan.containers()));
        caches.addAll(context.workspace().cacheContainers());
        return EmptyBoxSupplyPlan.sources(caches, null, List.of());
    }

    private List<ContainerTarget> emptyBoxWarehouse(TaskExecutionContext context) {
        var policy = context.workspace().cleanupPolicy().emptyBoxWarehouse();
        if (policy == null || !policy.enabled()) return List.of();
        return boxWarehouses.computeIfAbsent(context.workspace(), ignored -> {
            var scan = CacheLocations.scan(context, ResourceReference.parse(policy.warehouseRef()),
                    TemporaryContainerResolver.TYPES, 1_000_000, true);
            if (!scan.succeeded()) throw new IllegalStateException(scan.message());
            if (scan.containers().isEmpty()) {
                throw new IllegalStateException("空盒仓库中没有可用的箱子或木桶");
            }
            return scan.containers();
        });
    }

    @Override
    public TaskModuleExecutor settleEmptyBoxes(TaskExecutionContext context) {
        return new EmptyBoxSettlement(context);
    }

    private List<ContainerTarget> overflowContainers(ResourceReference destination) {
        String dimension = minecraft.level.dimension().identifier().toString();
        List<BlockPosition> points = destination == null ? settings.overflowPositions(dimension)
                : destination.type() == ResourceType.COORDINATE
                        ? settings.overflowPositions(dimension, destination.uuid()) : List.of();
        List<RegionDefinition> regions = destination == null ? settings.overflowRegions()
                : destination.type() == ResourceType.REGION
                        ? settings.overflowRegions(destination.uuid()) : List.of();
        return TemporaryContainerResolver.resolve(points, regions, containerRegions,
                Set.copyOf(settings.resolved().emptyBoxContainers()), "溢出");
    }

    private List<ContainerTarget> robotWarehouseContainers(TaskExecutionContext context,
            ResourceReference destination) {
        var scan = CacheLocations.scan(context, destination, TemporaryContainerResolver.TYPES,
                1_000_000, true);
        if (!scan.succeeded()) throw new IllegalStateException(scan.message());
        var reserved = taskStorage(context).emptyBoxContainers();
        return scan.containers().stream().filter(target ->
                target.entrances().stream().noneMatch(reserved::contains)).toList();
    }

    @Override
    public TaskModuleExecutor refreshContainerCache(TaskExecutionContext context, TaskStep step) {
        if (!(containerRegions instanceof MinecraftContainerRegionScanner scanner)) {
            throw new IllegalStateException("区域扫描组件不可用");
        }
        return new MinecraftContainerCacheRefresh(minecraft, scanner, context,
                ContainerCacheRefresh.from(step.parameters()));
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
        return taskCleanupContainers(context, taskStorage(context));
    }

    private StorageAutomationSettings taskStorage(TaskExecutionContext context) {
        var policy = context.workspace().cleanupPolicy();
        var empty = policy.emptyBoxWarehouse() == null
                ? policy.emptyBoxRefs().stream().map(ref -> taskPoint(context, ref))
                        .filter(Objects::nonNull).toList()
                : emptyBoxWarehouse(context).stream().flatMap(target -> target.entrances().stream())
                        .distinct().toList();
        var fallback = policy.packingPositionRef() == null ? null
                : taskPoint(context, policy.packingPositionRef());
        return new StorageAutomationSettings(false, List.of(), empty, fallback);
    }

    private BlockPosition taskPoint(TaskExecutionContext context, String ref) {
        var point = context.coordinates().get(ResourceReference.parse(ref).uuid());
        if (point.dimension() != null && !context.navigation().isInDimension(point.dimension())) {
            return null;
        }
        return new BlockPosition(point.x(), point.y(), point.z());
    }

    private List<ContainerTarget> taskCleanupContainers(TaskExecutionContext context,
            StorageAutomationSettings configuration) {
        var ref = context.workspace().cleanupPolicy().destinationRef();
        if (ref == null) return List.of();
        var scan = CacheLocations.scan(context, ResourceReference.parse(ref),
                TemporaryContainerResolver.TYPES, 1_000_000, true);
        if (!scan.succeeded()) throw new IllegalStateException(scan.message());
        var reserved = configuration.emptyBoxContainers();
        return scan.containers().stream()
                .filter(target -> target.entrances().stream().noneMatch(reserved::contains)).toList();
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

    private record CacheSnapshot(int slots, Map<String, Integer> counts, List<ItemStack> stacks,
            Map<String, Integer> stackSizes, Set<String> fullBoxKeys) {
        private static final CacheSnapshot EMPTY = new CacheSnapshot(
                0, Map.of(), List.of(), Map.of(), Set.of());
    }

    private static Map<String, Integer> emptyBoxCounts(List<ItemStack> stacks) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        stacks.stream().filter(MinecraftStorageAutomation::isEmptyBox).forEach(stack ->
                counts.merge(MinecraftItemIdentity.key(stack), stack.getCount(), Integer::sum));
        return counts;
    }

    private CacheSnapshot recordCacheContents(TaskExecutionContext context, ContainerTarget target,
            List<ItemStack> stacks) {
        List<ItemStack> occupied = stacks.stream().filter(stack -> !stack.isEmpty()).toList();
        context.workspace().recordCacheCapacity(target, stacks.size(), occupied.size());
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
                stacks.size(), Map.copyOf(counts), occupied, Map.copyOf(stackSizes),
                Set.copyOf(fullBoxKeys));
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
                sources.addAll(partialBoxContainers(context, policy, taskStorage(context)));
            }
        }
        return EmptyBoxSupplyPlan.sources(sources, null, List.of());
    }

    private abstract class Session implements TaskModuleExecutor {
        final TaskExecutionContext context;
        final StorageAutomationSettings configuration;
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
            this.configuration = taskStorage(context);
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
            var policy = context.workspace().cleanupPolicy().emptyBoxWarehouse();
            if (policy != null) {
                sources.clear();
                var caches = classificationCaches(context);
                int existing = caches.stream().mapToInt(target -> context.workspace()
                        .cacheContents(target).map(EmptyBoxSupplyPlan::emptyCount).orElse(0)).sum();
                sources.addAll(EmptyBoxSupplyPlan.classificationReturns(caches,
                        emptyBoxWarehouse(context), policy.enabled(), existing,
                        policy.retainedLimit()));
                return EmptyBoxSupplyPlan.returns(null, sources, List.of(), storagePositions,
                        context.workspace().partialBoxStoragePositions());
            }
            sources.addAll(emptyBoxSources(context));
            return EmptyBoxSupplyPlan.returns(null, sources,
                    taskCleanupContainers(context, configuration), storagePositions,
                    context.workspace().partialBoxStoragePositions());
        }

        final void returnEmptyBoxes(Runnable after) {
            returnEmptyBoxes(emptyBoxReturns(null), after);
        }

        final void returnEmptyBoxes(List<ContainerTarget> destinations, Runnable after) {
            returnEmptyBoxBatch(destinations, 0, after);
        }

        private void returnEmptyBoxBatch(List<ContainerTarget> destinations, int index,
                Runnable after) {
            if (EmptyBoxSupplyPlan.emptyCount(owned()) == 0) {
                after.run();
                return;
            }
            var policy = context.workspace().cleanupPolicy().emptyBoxWarehouse();
            if (policy != null && index == 0) destinations = emptyBoxReturns(null);
            if (index >= destinations.size()) {
                throw new IllegalStateException("配置的空盒存放位置无法接收空潜影盒，等待收尾");
            }
            var candidates = destinations;
            var target = candidates.get(index);
            int maximum = Integer.MAX_VALUE;
            if (policy != null && policy.enabled() && classificationCaches(context).stream()
                    .anyMatch(cache -> sameContainer(cache, target))) {
                int cached = classificationCaches(context).stream().mapToInt(cache -> context
                        .workspace().cacheContents(cache).map(EmptyBoxSupplyPlan::emptyCount)
                        .orElse(0)).sum();
                maximum = Math.max(0, policy.retainedLimit() - cached);
            }
            var batch = EmptyBoxSupplyPlan.returnBatch(owned(), maximum);
            if (batch.isEmpty()) {
                returnEmptyBoxBatch(candidates, index + 1, after);
                return;
            }
            int before = EmptyBoxSupplyPlan.emptyCount(owned());
            var access = MinecraftStorageAccess.returnEmptyBoxes(minecraft, context, target, batch);
            Runnable confirmed = () -> {
                boolean progress = EmptyBoxSupplyPlan.emptyCount(owned()) < before;
                if (progress) refreshCacheSnapshot(access);
                boolean complete = access.remaining().values().stream().allMatch(count -> count == 0);
                returnEmptyBoxBatch(candidates, progress && complete ? 0 : index + 1, after);
            };
            steps.add(new Step(access, confirmed, confirmed));
        }

        final void storeEmptyBox(ItemStack empty, List<ContainerTarget> destinations, int index,
                Runnable after) {
            var policy = context.workspace().cleanupPolicy().emptyBoxWarehouse();
            if (policy != null && index == 0) destinations = emptyBoxReturns(null);
            if (index >= destinations.size()) {
                throw new IllegalStateException("配置的空盒存放位置无法接收空潜影盒，等待收尾");
            }
            final List<ContainerTarget> candidates = destinations;
            String key = MinecraftItemIdentity.key(empty);
            int before = owned().getOrDefault(key, 0);
            var access = MinecraftStorageAccess.returnEmptyBox(minecraft, context,
                    destinations.get(index), key);
            Runnable confirmed = () -> {
                if (owned().getOrDefault(key, 0) == before - 1) after.run();
                else storeEmptyBox(empty, candidates, index + 1, after);
            };
            steps.add(new Step(access, confirmed, confirmed));
        }

        final UUID owner() {
            return context.workspace().inventoryAccount().ownerId();
        }

        final void recover(Runnable after) {
            IntakeStash stash = intakeStashes.get(owner());
            if (stash != null) {
                restoreIntakeStash(stash, after);
                return;
            }
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

        private void restoreIntakeStash(IntakeStash stash, Runnable after) {
            var resource = resources.get(owner());
            boolean full = minecraft.player.getInventory().getNonEquipmentItems().stream()
                    .noneMatch(ItemStack::isEmpty);
            if (full) {
                if (resource == null) {
                    throw new IllegalStateException("任务物品暂存在空盒供应箱，请腾出一格后继续收尾");
                }
                if (!resource.placed) {
                    steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, true),
                            () -> restoreIntakeStash(stash, after)));
                    return;
                }
                var placed = new ContainerTarget(resource.position, "minecraft:shulker_box");
                read(placed, contents -> {
                    Map<String, Integer> raw = new LinkedHashMap<>();
                    owned().forEach((key, count) -> {
                        if (!ItemIdentity.isBox(key)) raw.put(key, count);
                    });
                    Map<String, Integer> fitting = ShulkerTransferPlanner.fitting(
                            transferSlots(contents), contents.size(), raw, inventoryStackSizes());
                    if (fitting.isEmpty()) {
                        throw new IllegalStateException("暂存物品仍在空盒供应箱，请腾出一格后继续收尾");
                    }
                    transfer(placed, fitting, false, () -> restoreIntakeStash(stash, after));
                });
                return;
            }
            Map<String, Integer> before = owned();
            transfer(stash.supply(), stash.items(), true, () -> {
                boolean restored = stash.items().entrySet().stream().allMatch(entry ->
                        owned().getOrDefault(entry.getKey(), 0)
                                >= before.getOrDefault(entry.getKey(), 0) + entry.getValue());
                if (!restored) {
                    throw new IllegalStateException("空盒供应箱中的暂存任务物品未取回，等待收尾");
                }
                intakeStashes.remove(owner());
                recover(after);
            });
        }

        private void makeRecoverySpace(MinecraftShulkerAction.Resource resource, int index,
                Runnable after) {
            List<ContainerTarget> destinations = taskCleanupContainers(context, configuration)
                    .stream().filter(target -> !target.entrances().contains(resource.position))
                    .toList();
            context.inventory().observe();
            for (ItemStack stack : inventoryStacks()) {
                if (MinecraftItemIdentity.isBox(stack)) continue;
                String key = MinecraftItemIdentity.key(stack);
                if (owned().getOrDefault(key, 0) >= stack.getCount()) break;
                if (context.inventory().claimRecoveryInput(context.workspace().inventoryAccount(),
                        key, stack.getCount()) > 0) break;
            }
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

    /** Experimental intake packing; ordinary collection never creates this session. */
    private final class CacheIntake extends Session {
        private final List<ContainerTarget> caches;
        private final boolean pack;
        private ContainerVisitPlan deposits;
        private int compressionCursor;
        private boolean exchanging;
        private boolean stopAfterExchange;
        private BlockPosition site;

        CacheIntake(TaskExecutionContext context, List<ContainerTarget> caches, boolean pack) {
            super(context);
            this.caches = List.copyOf(caches);
            this.pack = pack;
        }

        @Override
        void initialize() {
            if (caches.isEmpty()) throw new IllegalStateException("缓存区域没有可用容器");
            if (hasTemporaryResources(owner())) {
                throw new IllegalStateException("缓存装盒前请先完成上次潜影盒收尾");
            }
            if (pack) packNext();
            else beginDeposit();
        }

        private Map<String, Integer> rawOwned() {
            Map<String, Integer> result = new LinkedHashMap<>();
            owned().forEach((key, count) -> {
                if (!ItemIdentity.isBox(key)) result.put(key, count);
            });
            return result;
        }

        private void packNext() {
            if (rawOwned().isEmpty()) {
                beginDeposit();
                return;
            }
            site = chooseSite(context, caches.getFirst(), null, configuration);
            ItemStack empty = ownedEmptyBox(owned());
            if (!empty.isEmpty()) place(empty);
            else if (minecraft.player.getInventory().getNonEquipmentItems().stream()
                    .anyMatch(ItemStack::isEmpty)) {
                withdrawEmptyBox(0, this::place);
            } else {
                exchangeEmptyBox(0);
            }
        }

        private void exchangeEmptyBox(int index) {
            List<ContainerTarget> supplies = emptyBoxSources(context);
            if (index >= supplies.size()) {
                throw new IllegalStateException("空盒供应不足，无法为满背包打包，请补充空潜影盒");
            }
            ContainerTarget supply = supplies.get(index);
            var exchange = new MinecraftEmptyBoxExchange(minecraft, context, supply);
            exchanging = true;
            steps.add(new Step(exchange, () -> {
                intakeStashes.put(owner(), new IntakeStash(supply, exchange.stashed()));
                place(exchange.box());
                exchanging = false;
                if (stopAfterExchange) super.requestStop();
            }, () -> {
                exchanging = false;
                if (stopAfterExchange) super.requestStop();
                else exchangeEmptyBox(index + 1);
            }));
        }

        private void place(ItemStack empty) {
            var resource = new MinecraftShulkerAction.Resource(site, empty);
            resources.put(owner(), resource);
            var target = new ContainerTarget(site, "minecraft:shulker_box");
            steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, true),
                    () -> read(target, contents -> {
                        Map<String, Integer> fitting = ShulkerTransferPlanner.fitting(
                                transferSlots(contents), contents.size(), rawOwned(),
                                inventoryStackSizes());
                        if (fitting.isEmpty()) {
                            throw new IllegalStateException("潜影盒无法接收当前投料");
                        }
                        int before = sum(rawOwned());
                        transfer(target, fitting, false, () -> {
                            if (sum(rawOwned()) >= before) {
                                throw new IllegalStateException("投料装盒未确认，等待收尾");
                            }
                            recover(this::packNext);
                        });
                    })));
        }

        private void beginDeposit() {
            deposits = new ContainerVisitPlan(caches);
            depositNext();
        }

        private void depositNext() {
            if (owned().isEmpty()) return;
            BlockPos feet = minecraft.player.blockPosition();
            Map<String, Integer> incoming = owned();
            Map<String, Integer> sizes = inventoryStackSizes();
            ContainerTarget cache = deposits.next(new BlockPosition(feet.getX(), feet.getY(),
                    feet.getZ()), candidate -> intakeSnapshot(candidate)
                            .map(snapshot -> !ShulkerTransferPlanner.fittingInventory(
                                    transferSlots(snapshot.stacks()), snapshot.slots(),
                                    incoming, sizes).isEmpty()).orElse(true)).orElse(null);
            if (cache == null) {
                if (pack && compressionCursor < caches.size()) loadCacheRaw();
                return;
            }
            var access = new MinecraftStorageAccess(minecraft, context, cache, incoming,
                    false, false);
            steps.add(new Step(access, this::depositNext, this::depositNext));
        }

        private Optional<CacheSnapshot> intakeSnapshot(ContainerTarget cache) {
            // Classification invalidates capacity before returning to the intake workspace.
            return context.workspace().hasCacheCapacity(cache)
                    ? confirmedCacheSnapshot(context, cache) : Optional.empty();
        }

        private void loadCacheRaw() {
            if (compressionCursor >= caches.size() || compressionSlots() == 0) {
                if (!rawOwned().isEmpty()) packNext();
                return;
            }
            ContainerTarget cache = caches.get(compressionCursor++);
            Consumer<List<ItemStack>> compress = contents -> {
                Set<String> raw = contents.stream().filter(stack -> !stack.isEmpty()
                        && !MinecraftItemIdentity.isBox(stack))
                        .map(MinecraftItemIdentity::key).map(ItemIdentity::exact)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (raw.isEmpty()) {
                    loadCacheRaw();
                } else {
                    var withdrawal = new ContainerBatchWithdrawal(context, cache, raw,
                            compressionSlots());
                    steps.add(new Step(withdrawal, this::loadCacheRaw));
                }
            };
            intakeSnapshot(cache).map(CacheSnapshot::stacks).ifPresentOrElse(compress,
                    () -> readCache(cache, compress));
        }

        private int compressionSlots() {
            long empty = minecraft.player.getInventory().getNonEquipmentItems().stream()
                    .filter(ItemStack::isEmpty).count();
            return Math.min(remainingBatchSlots(), Math.max(0, (int) empty - 1));
        }

        @Override
        public void requestStop() {
            stopAfterExchange = true;
            if (!exchanging) super.requestStop();
        }

        @Override
        public boolean safeToStop() {
            return !exchanging && super.safeToStop();
        }

        @Override
        String completionMessage() {
            return owned().isEmpty() ? "投料已存入缓存" : "缓存无法接收更多物品，需要先分类腾出空间";
        }
    }

    private final class MaterialCollection extends Session {
        private final ResourceReference destination;
        private final String sourceZoneRef;
        private final boolean boxedDelivery;
        private final boolean singleMaterialBoxes;
        private final Map<String, Integer> needed;
        private final Map<String, String> materialByKey = new LinkedHashMap<>();
        private final Map<String, Map<String, Integer>> boxContentsByKey = new LinkedHashMap<>();
        private final Map<BlockPosition, Map<String, Integer>> observedDeliveries =
                new LinkedHashMap<>();
        private List<ContainerTarget> destinations;
        private List<ContainerTarget> sources;
        private List<MaterialSource> warehouseSources;
        private List<String> materialOrder;
        private int materialIndex;
        private String packingMaterial;
        private String partialBoxKey;
        private AreaBounds destinationBounds;
        private String dimension;
        private String warehouseProblem;
        private boolean canPackLoose;
        private int sourceIndex;
        private int unavailableSources;
        private boolean returningBox;
        private boolean stopAfterBox;

        MaterialCollection(TaskExecutionContext context, ResourceReference destination,
                String sourceZoneRef, Map<String, Integer> required, boolean boxedDelivery,
                boolean singleMaterialBoxes) {
            super(context);
            this.destination = destination;
            this.sourceZoneRef = sourceZoneRef;
            this.boxedDelivery = boxedDelivery;
            this.singleMaterialBoxes = boxedDelivery && singleMaterialBoxes;
            needed = new LinkedHashMap<>(required);
        }

        @Override
        void initialize() {
            if (needed.isEmpty()) {
                return;
            }
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
            canPackLoose = !boxedDelivery || !emptyBoxSources(context).isEmpty();
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
            var sourceAreas = sourceZoneRef == null ? List.<AreaBounds>of()
                    : context.categoryZones().get(ResourceReference.parse(sourceZoneRef).uuid())
                            .bindings().stream().map(binding -> binding.storageArea()).toList();
            warehouseSources = stock.containers().stream()
                    .filter(container -> dimension.equals(container.dimension()))
                    .filter(container -> sourceZoneRef == null
                            || ChestTrackerInventorySource.inAreas(
                                    container.positions(), sourceAreas))
                    .filter(container -> container.positions().stream().noneMatch(
                            configuration.emptyBoxContainers()::contains))
                    .filter(container -> destinationBounds == null
                            || container.positions().stream()
                                    .noneMatch(destinationBounds::contains))
                    .map(MaterialSource::from)
                    .filter(source -> needed.keySet().stream().anyMatch(source::has))
                    .filter(source -> destinations.stream().noneMatch(
                            target -> sameContainer(source.target(), target)))
                    .toList();
            if (singleMaterialBoxes) {
                materialOrder = List.copyOf(needed.keySet());
                nextMaterial();
            } else {
                sources = sortedSources(warehouseSources);
                nextSource();
            }
        }

        private List<ContainerTarget> sortedSources(List<MaterialSource> candidates) {
            return candidates.stream().map(MaterialSource::target).sorted(
                    Comparator.comparingDouble(MinecraftStorageAutomation.this::storageDistance))
                    .toList();
        }

        private void nextMaterial() {
            while (materialIndex < materialOrder.size()) {
                packingMaterial = materialOrder.get(materialIndex++);
                if (!needed.containsKey(packingMaterial)) continue;
                var candidates = warehouseSources.stream()
                        .filter(source -> source.has(packingMaterial)).toList();
                if (candidates.isEmpty()) continue;
                sources = sortedSources(candidates);
                sourceIndex = 0;
                nextSource();
                return;
            }
            if (!needed.isEmpty()) {
                finishShort();
            }
        }

        private void nextSource() {
            if (needed.isEmpty()) {
                return;
            }
            if (singleMaterialBoxes && !needed.containsKey(packingMaterial)) {
                nextMaterial();
                return;
            }
            if (sourceIndex >= sources.size()) {
                flush(true, () -> {
                    if (singleMaterialBoxes) nextMaterial();
                    else if (!needed.isEmpty()) finishShort();
                });
                return;
            }
            if (hasFullBatch()) {
                flush(uncollected().isEmpty(), this::nextSource);
                return;
            }
            collectFrom(sources.get(sourceIndex++));
        }

        private void collectFrom(ContainerTarget source) {
            if (needed.isEmpty()) {
                return;
            }
            var access = MinecraftStorageAccess.collect(minecraft, context, source,
                    this::planWithdrawal);
            steps.add(new Step(access, () -> {
                var contents = access.contents();
                if (hasFullBatch()) {
                    flush(uncollected().isEmpty(), () -> continueSource(source, contents));
                } else {
                    unpack(source, contents);
                }
            }, this::skipSource));
        }

        private boolean hasFullBatch() {
            var batch = owned();
            if (batch.isEmpty()) return false;
            boolean transferable = partialBoxKey == null || batch.size() > 1
                    || batch.getOrDefault(partialBoxKey, 0) > 1;
            return uncollected().isEmpty()
                    || transferable && freeInventorySlots() <= packingReserve();
        }

        private int packingReserve() {
            return boxedDelivery && partialBoxKey == null && owned().keySet().stream()
                    .anyMatch(key -> !ItemIdentity.isBox(key)) ? 1 : 0;
        }

        private long freeInventorySlots() {
            return minecraft.player.getInventory().getNonEquipmentItems().stream()
                    .filter(ItemStack::isEmpty).count();
        }

        private void continueSource(ContainerTarget source, List<ItemStack> contents) {
            if (singleMaterialBoxes && !needed.containsKey(packingMaterial)) {
                nextMaterial();
                return;
            }
            var remaining = uncollected();
            if (remaining.isEmpty()) {
                nextSource();
                return;
            }
            if (contents.stream().anyMatch(stack -> remaining.containsKey(material(stack))
                    || MinecraftItemIdentity.isBox(stack) && !isEmptyBox(stack)
                            && materialsInBox(stack).keySet().stream().anyMatch(
                                    remaining::containsKey))) {
                collectFrom(source);
            } else {
                nextSource();
            }
        }

        private Map<String, Integer> planWithdrawal(List<ItemStack> contents) {
            Map<String, Integer> remaining = uncollected();
            Map<String, Integer> wanted = new LinkedHashMap<>();
            int free = (int) freeInventorySlots();
            int packedSlots = 0;
            if (boxedDelivery) {
                boolean hasLooseOrPartial = partialBoxKey != null || packingReserve() != 0;
                int slotsForBoxes = Math.max(0, free - packingReserve());
                for (ItemStack stack : contents) {
                    if (slotsForBoxes == 0) break;
                    if (!MinecraftItemIdentity.isBox(stack) || isEmptyBox(stack)) continue;
                    var materials = materialsInBox(stack);
                    if (singleMaterialBoxes && !MaterialRequirements.canDeliverBox(
                            packingMaterial, remaining, materials,
                            MinecraftItemIdentity.full(stack), hasLooseOrPartial)) continue;
                    int count = MaterialRequirements.reserveBoxes(remaining, materials,
                            Math.min(stack.getCount(), slotsForBoxes));
                    if (count > 0) {
                        String key = MinecraftItemIdentity.key(stack);
                        boxContentsByKey.put(key, materials);
                        wanted.merge(key, count, Math::addExact);
                        slotsForBoxes -= count;
                        packedSlots += count;
                    }
                }
            }
            boolean canTakeLoose = canPackLoose
                    && (!boxedDelivery || partialBoxKey != null || packedSlots < free);
            Map<String, Integer> loose = matching(contents, remaining);
            loose.forEach((key, count) -> {
                if (canTakeLoose || ItemIdentity.isBox(key)) {
                    wanted.put(key, count);
                } else if (!canPackLoose) {
                    warehouseProblem = "盒装散料需要在任务中选择空潜影盒供应箱";
                }
            });
            int reserve = boxedDelivery && canTakeLoose && partialBoxKey == null
                    ? 1 : packingReserve();
            return fitInventory(contents, wanted, reserve);
        }

        private void unpack(ContainerTarget source, List<ItemStack> contents) {
            var remaining = uncollected();
            ItemStack box = contents.stream().filter(MinecraftItemIdentity::isBox)
                    .filter(stack -> materialsInBox(stack).keySet().stream()
                            .anyMatch(remaining::containsKey))
                    .findFirst().orElse(ItemStack.EMPTY);
            if (box.isEmpty()) {
                nextSource();
                return;
            }
            if (!canPackLoose) {
                warehouseProblem = "装盒或拆取部分库存需要配置空潜影盒供应";
                nextSource();
                return;
            }
            if (!cleanupAvailable()) {
                throw new IllegalStateException("拆取盒装材料前请配置任务中断存放位置");
            }
            if (freeInventorySlots() == 0) {
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
                        () -> takeBoxMaterials(source, boxTarget, resource)));
            }));
        }

        private void takeBoxMaterials(ContainerTarget source, ContainerTarget boxTarget,
                MinecraftShulkerAction.Resource resource) {
            var before = owned();
            var access = MinecraftStorageAccess.collect(minecraft, context, boxTarget,
                    contents -> fitInventory(contents, matching(contents, uncollected()), 1));
            Runnable settled = () -> {
                boolean acquired = !owned().equals(before);
                recover(() -> returnSourceBox(source, resource, acquired));
            };
            steps.add(new Step(access, settled, settled));
        }

        private void returnSourceBox(ContainerTarget source, MinecraftShulkerAction.Resource resource,
                boolean acquired) {
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
                } else if (hasFullBatch()) {
                    flush(uncollected().isEmpty(), () -> {
                        if (singleMaterialBoxes && !needed.containsKey(packingMaterial)) {
                            nextMaterial();
                        } else {
                            collectFrom(source);
                        }
                    });
                } else if (acquired || deliveredDuringRecovery) {
                    collectFrom(source);
                } else {
                    skipSource();
                }
            }));
        }

        private Map<String, Integer> fitInventory(List<ItemStack> contents,
                Map<String, Integer> wanted, int reserve) {
            var inventory = minecraft.player.getInventory().getNonEquipmentItems();
            Map<String, Integer> sizes = new LinkedHashMap<>();
            contents.stream().filter(stack -> !stack.isEmpty()).forEach(stack ->
                    sizes.put(MinecraftItemIdentity.key(stack), stack.getMaxStackSize()));
            return ShulkerTransferPlanner.fittingInventory(transferSlots(inventory),
                    inventory.size() - reserve, wanted, sizes);
        }

        private void flush(boolean finishBox, Runnable after) {
            if (boxedDelivery) {
                packLoose(() -> {
                    if (finishBox) partialBoxKey = null;
                    deliver(0, after);
                });
            } else {
                deliver(0, after);
            }
        }

        private void packLoose(Runnable after) {
            Map<String, Integer> loose = new LinkedHashMap<>(owned());
            loose.keySet().removeIf(ItemIdentity::isBox);
            if (loose.isEmpty()) {
                after.run();
                return;
            }
            if (partialBoxKey == null) {
                returningBox = true;
                withdrawEmptyBox(0, empty -> packInto(empty, fittingBoxItems(empty, loose), after));
                return;
            }
            ItemStack currentBox = inventoryStacks().stream()
                    .filter(stack -> partialBoxKey.equals(MinecraftItemIdentity.key(stack)))
                    .findFirst().orElseThrow(() -> new IllegalStateException("待补满的潜影盒不在背包中"));
            var fits = fittingBoxItems(currentBox, loose);
            if (fits.isEmpty()) {
                partialBoxKey = null;
                deliver(0, () -> packLoose(after));
                return;
            }
            packInto(currentBox, fits, after);
        }

        private void packInto(ItemStack box, Map<String, Integer> items, Runnable after) {
            returningBox = true;
            BlockPosition site = chooseSite(context, destinations.getFirst(), null, configuration);
            var resource = new MinecraftShulkerAction.Resource(site, box);
            resources.put(owner(), resource);
            var boxTarget = new ContainerTarget(site, "minecraft:shulker_box");
            steps.add(new Step(new MinecraftShulkerAction(minecraft, context, resource, true),
                    () -> transfer(boxTarget, items, false,
                            () -> recover(() -> finishPacking(resource, after)))));
        }

        private void finishPacking(MinecraftShulkerAction.Resource resource, Runnable after) {
            returningBox = false;
            var contents = materialsInBox(resource.expected);
            if (contents.isEmpty()) {
                throw new IllegalStateException("未能将材料装入潜影盒，剩余材料等待收尾");
            }
            String key = MinecraftItemIdentity.key(resource.expected);
            boxContentsByKey.put(key, contents);
            partialBoxKey = MinecraftItemIdentity.full(resource.expected) ? null : key;
            if (stopAfterBox) {
                super.requestStop();
            } else {
                packLoose(after);
            }
        }

        private void deliver(int index, Runnable after) {
            Map<String, Integer> batch = new LinkedHashMap<>(owned());
            if (boxedDelivery) {
                batch.keySet().removeIf(key -> !ItemIdentity.isBox(key));
                if (partialBoxKey != null) {
                    batch.computeIfPresent(partialBoxKey, (ignored, count) ->
                            count > 1 ? count - 1 : null);
                }
            }
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

        private Map<String, Integer> uncollected() {
            Map<String, Integer> remaining = new LinkedHashMap<>();
            if (!singleMaterialBoxes) {
                remaining.putAll(needed);
            } else if (needed.containsKey(packingMaterial)) {
                remaining.put(packingMaterial, needed.get(packingMaterial));
            }
            owned().forEach((key, count) -> forEachMaterial(key, count, (material, amount) ->
                    remaining.computeIfPresent(material, (ignored, left) ->
                            amount < left ? left - amount : null)));
            return remaining;
        }

        private Map<String, Integer> matching(List<ItemStack> stacks,
                Map<String, Integer> remaining) {
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

        private Map<String, Integer> materialsInBox(ItemStack box) {
            Map<String, Integer> contents = new LinkedHashMap<>();
            MinecraftItemIdentity.contents(box).nonEmptyItemCopyStream().forEach(stack ->
                    contents.merge(material(stack), stack.getCount(), Math::addExact));
            return contents;
        }

        private void forEachMaterial(String key, int count,
                java.util.function.ObjIntConsumer<String> consumer) {
            var contents = boxContentsByKey.get(key);
            if (contents != null) {
                contents.forEach((material, amount) -> consumer.accept(material, amount * count));
            } else {
                consumer.accept(materialByKey.get(key), count);
            }
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
                    current.forEach((key, count) -> forEachMaterial(key,
                            count - previous.getOrDefault(key, 0), this::credit));
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

    private record MaterialSource(ContainerTarget target, Set<String> items) {
        static MaterialSource from(WarehouseInventory.Container container) {
            var positions = container.positions();
            var target = new ContainerTarget(positions.getFirst(), "container", null, null,
                    positions.size() > 1 ? positions.get(1) : null);
            var items = container.items().stream().map(WarehouseInventory.Entry::itemId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new MaterialSource(target, items);
        }

        boolean has(String material) {
            return items.contains(material) || MaterialRequirements.WATER_BOTTLE.equals(material)
                    && items.contains("minecraft:potion");
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
                throw new IllegalStateException("请配置任务中断存放位置，并在缓存或任务空盒仓库准备空盒");
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
        private final ResourceReference cacheLocation;
        private final boolean boxedOnly;
        private final ResourceReference destination;
        private final String remainderKind;
        private final boolean mixedBoxes;
        private String skipMessage;
        private boolean warehouseReady;
        private String warehouseScope;
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
        private final Map<ContainerTarget, List<ItemStack>> checkedStorage = new LinkedHashMap<>();
        private final Map<ContainerTarget, List<ContainerTarget>> partialStorage =
                new LinkedHashMap<>();
        private Set<String> eligibleItems = Set.of();

        Overflow(TaskExecutionContext context, ResourceReference cacheLocation, boolean boxedOnly,
                ResourceReference destination) {
            this(context, cacheLocation, boxedOnly, destination, null);
        }

        Overflow(TaskExecutionContext context, ResourceReference cacheLocation, boolean boxedOnly,
                ResourceReference destination, String remainderKind) {
            this(context, cacheLocation, boxedOnly, destination, remainderKind, true);
        }

        Overflow(TaskExecutionContext context, ResourceReference cacheLocation, boolean boxedOnly,
                ResourceReference destination, String remainderKind, boolean mixedBoxes) {
            super(context);
            this.cacheLocation = cacheLocation;
            this.boxedOnly = boxedOnly;
            this.destination = destination;
            this.remainderKind = remainderKind;
            this.mixedBoxes = mixedBoxes;
        }

        @Override
        void initialize() {
            initialized = true;
            if (boxedOnly && destination == null) {
                skipMessage = "请选择任务的溢出仓库，物品留在缓存";
                return;
            }
            if (cacheLocation == null && owned().isEmpty()) return;
            if (remainderKind == null) warehouseScope = robotWarehouseScope();
            destinations = boxedOnly || remainderKind != null
                    ? robotWarehouseContainers(context, destination)
                    : overflowContainers(destination);
            if (destination != null && destinations.isEmpty()) {
                skipMessage = "所选仓库位置在当前维度无可用容器，溢出物品留在缓存";
                return;
            }
            unstackableRegion = boxedOnly || remainderKind != null
                    ? null : settings.unstackableOverflowRegion();
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
            if (cacheLocation != null) {
                var scan = CacheLocations.scan(context, cacheLocation, Set.of(), 1_000_000, true);
                if (!scan.succeeded()) throw new IllegalStateException(scan.message());
                destinations = destinations.stream().filter(target -> scan.containers().stream()
                        .noneMatch(cache -> sameContainer(target, cache))).toList();
                if ((boxedOnly || remainderKind != null) && destinations.isEmpty()) {
                    skipMessage = "溢出仓库没有独立于缓存的容器，物品留在缓存";
                    return;
                }
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
            Map<String, Integer> managed = owned();
            List<ItemStack> carried = inventoryStacks().stream()
                    .filter(stack -> managed.containsKey(MinecraftItemIdentity.key(stack)))
                    .toList();
            verifyOverflow(carried, null, this::packOwned);
        }

        private void prepareWarehouse() {
            if (remainderKind != null) return;
            if (warehouseReady) return;
            if (!cleanupAvailable()) {
                throw new IllegalStateException("溢出打包前请配置任务中断存放位置");
            }
            if (destinations.isEmpty()) {
                throw new IllegalStateException(boxedOnly ? "请选择任务的机器人仓库"
                        : "请先在公共设施配置溢出箱或溢出区域");
            }
            robotWarehouse.prepare();
            warehouseReady = true;
        }

        private void packOwned() {
            if (remainderKind != null) {
                storeRoutedItems(0);
                return;
            }
            if (!rawOwned().isEmpty()) {
                prepareWarehouse();
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
                    .filter(this::eligibleBox)
                    .findFirst().orElse(ItemStack.EMPTY);
            if (!filled.isEmpty()) {
                prepareWarehouse();
                box = filled;
                reusedFrom = null;
                if (!mixedBoxes && ItemIdentity.MIXED_BOX_CATEGORY.equals(
                        MinecraftItemIdentity.boxCategory(box))) {
                    String category = MinecraftItemIdentity.contents(box).nonEmptyItemCopyStream()
                            .map(MinecraftItemIdentity::base).sorted().findFirst().orElseThrow();
                    Set<String> selectors = MinecraftItemIdentity.contents(box)
                            .nonEmptyItemCopyStream()
                            .filter(item -> category.equals(MinecraftItemIdentity.base(item)))
                            .map(MinecraftItemIdentity::key).map(ItemIdentity::exact)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet());
                    unpackOverflowBox(selectors, true);
                    return;
                }
                Set<String> unstackable = unstackableRegion == null ? Set.of()
                        : MinecraftItemIdentity.contents(box).nonEmptyItemCopyStream()
                                .filter(MinecraftStorageAutomation::isUnstackableItem)
                                .map(MinecraftItemIdentity::key).map(ItemIdentity::exact)
                                .collect(java.util.stream.Collectors.toUnmodifiableSet());
                if (!unstackable.isEmpty()) {
                    unpackOverflowBox(unstackable, true);
                } else if (boxedOnly || MinecraftItemIdentity.full(box)
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

        private Map<String, Integer> routedOwned() {
            Map<String, Integer> result = new LinkedHashMap<>(rawOwned());
            for (ItemStack stack : inventoryStacks()) {
                if (!MinecraftItemIdentity.isBox(stack) || isEmptyBox(stack)
                        || !eligibleBox(stack)) continue;
                String key = MinecraftItemIdentity.key(stack);
                int count = owned().getOrDefault(key, 0);
                if (count > 0) result.put(key, count);
            }
            return result;
        }

        private void storeRoutedItems(int index) {
            Map<String, Integer> batch = routedOwned();
            if (batch.isEmpty()) {
                if (currentSource == null) visitSource();
                else drainSource();
                return;
            }
            if (index >= destinations.size()) {
                returnRoutedItems(0);
                return;
            }
            int before = sum(batch);
            transfer(destinations.get(index), batch, false, () -> {
                storedOrdinary |= sum(routedOwned()) < before;
                storeRoutedItems(index + 1);
            });
        }

        private void returnRoutedItems(int index) {
            Map<String, Integer> batch = routedOwned();
            if (batch.isEmpty()) {
                currentSource = null;
                visitSource();
                return;
            }
            List<ContainerTarget> returns = new ArrayList<>();
            if (currentSource != null) returns.add(currentSource);
            returns.addAll(cacheSources);
            if (index >= returns.size()) {
                throw new IllegalStateException("存放位置已满且余料无法归还物品来源，等待收尾");
            }
            transfer(returns.get(index), batch, false, () -> returnRoutedItems(index + 1));
        }

        private Map<String, Integer> unstackableOwned() {
            if (unstackableRegion == null) return Map.of();
            Map<String, Integer> managed = owned();
            Map<String, Integer> items = new LinkedHashMap<>();
            for (ItemStack stack : inventoryStacks()) {
                if (!isUnstackableItem(stack)) continue;
                String key = MinecraftItemIdentity.key(stack);
                int count = managed.getOrDefault(key, 0);
                if (count > 0 && eligibleItems.contains(key)) items.put(key, count);
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
            ContainerTarget target = destinations.get(index);
            read(target, contents -> reuseWarehouseBox(target, contents, index + 1),
                    () -> findReusableOverflowBox(index + 1));
        }

        private void reuseWarehouseBox(ContainerTarget destination, List<ItemStack> contents,
                int following) {
            Location location = warehouseLocation(destination);
            Map<String, RobotWarehouseCache.Box> observed = new LinkedHashMap<>();
            List<ItemStack> warehouseBoxes = new ArrayList<>();
            for (ItemStack stack : contents) {
                if (!MinecraftItemIdentity.isBox(stack)) continue;
                String fingerprint = boxFingerprint(stack);
                RobotWarehouseCache.Box previous = observed.get(fingerprint);
                int count = stack.getCount() + (previous == null ? 0 : previous.count());
                observed.put(fingerprint, new RobotWarehouseCache.Box(
                        warehouseBoxContents(stack), count));
                warehouseBoxes.add(stack);
            }
            robotWarehouse.observed(location, observed);
            var candidates = warehouseBoxes.stream()
                    .filter(stack -> !unusableBoxes.contains(destination.position() + ":"
                            + MinecraftItemIdentity.key(stack)))
                    .map(stack -> new RobotWarehousePackingPlan.Box(MinecraftItemIdentity.key(stack),
                            boxSlots(stack))).toList();
            var selected = RobotWarehousePackingPlan.select(candidates, rawOwned(),
                    inventoryStackSizes(), mixedBoxes);
            if (selected.isEmpty()) {
                findReusableOverflowBox(following);
                return;
            }
            String key = selected.orElseThrow();
            ItemStack reusable = warehouseBoxes.stream()
                    .filter(stack -> key.equals(MinecraftItemIdentity.key(stack)))
                    .findFirst().orElseThrow().copy();
            String fingerprint = boxFingerprint(reusable);
            int before = owned().getOrDefault(key, 0);
            var access = MinecraftStorageAccess.warehouseBox(minecraft, context, destination,
                    key, true, () -> robotWarehouse.withdrawn(location, fingerprint));
            Runnable acquired = () -> {
                if (owned().getOrDefault(key, 0) == before + 1) {
                    box = reusable;
                    reusedFrom = destination;
                    placeOverflowBox();
                } else {
                    unusableBoxes.add(destination.position() + ":" + key);
                    findReusableOverflowBox(following);
                }
            };
            steps.add(new Step(access, acquired, acquired));
        }

        private Location warehouseLocation(ContainerTarget target) {
            return new Location(warehouseScope, target.position());
        }

        private String boxFingerprint(ItemStack stack) {
            return MinecraftItemIdentity.persistentKey(stack, minecraft.level.registryAccess());
        }

        private Map<String, Integer> warehouseBoxContents(ItemStack stack) {
            Map<String, Integer> contents = new LinkedHashMap<>();
            MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream().forEach(item ->
                    contents.merge(MinecraftItemIdentity.base(item), item.getCount(),
                            Math::addExact));
            return contents;
        }

        private void useOwnedOrEmptyBox() {
            reusedFrom = null;
            box = inventoryStacks().stream().filter(MinecraftItemIdentity::isBox)
                    .filter(stack -> owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                    .filter(this::eligibleBox)
                    .filter(stack -> !RobotWarehousePackingPlan.fitting(boxSlots(stack),
                            StoragePackingPlanner.BOX_SLOTS, rawOwned(), inventoryStackSizes(),
                            mixedBoxes).isEmpty())
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
            unpackOverflowBox(items, drain -> {
                if (drain.withdrawn().isEmpty()) {
                    if (requireItems) {
                        throw new IllegalStateException("未能取出潜影盒中的目标物品");
                    }
                    undrainableBoxes.add(MinecraftItemIdentity.key(box));
                }
                packOwned();
            });
        }

        private void unpackOverflowBox(Set<String> items, Consumer<ContainerBatchWithdrawal> after) {
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
                        after.accept(drain);
                    })))));
        }

        @Override
        public TaskModuleResult tick() {
            return initialized ? super.tick() : super.start();
        }

        private Map<String, Integer> rawOwned() {
            Map<String, Integer> items = new LinkedHashMap<>();
            owned().forEach((key, count) -> {
                if (!ItemIdentity.isBox(key) && eligibleItems.contains(key)) items.put(key, count);
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
                Map<String, Integer> requested = RobotWarehousePackingPlan.fitting(slots,
                        contents.size(), rawOwned(), inventoryStackSizes(), mixedBoxes);
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
            ContainerTarget destination = returns.get(index);
            Location location = warehouseLocation(destination);
            String fingerprint = boxFingerprint(box);
            Map<String, Integer> contents = warehouseBoxContents(box);
            var access = MinecraftStorageAccess.warehouseBox(minecraft, context, destination,
                    key, false, () -> robotWarehouse.stored(location, fingerprint, contents));
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
            read(currentSource, contents -> verifyOverflow(contents, currentSource,
                    () -> drainVerifiedSource(contents)));
        }

        private void verifyOverflow(List<ItemStack> contents, ContainerTarget source,
                Runnable after) {
            Map<String, ItemStack> candidates = new LinkedHashMap<>();
            for (ItemStack stack : contents) {
                if (stack.isEmpty()) continue;
                if (MinecraftItemIdentity.isBox(stack)) {
                    MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream().forEach(item ->
                            candidates.putIfAbsent(MinecraftItemIdentity.key(item), item));
                } else {
                    candidates.putIfAbsent(MinecraftItemIdentity.key(stack), stack);
                }
            }
            Map<String, List<ContainerTarget>> routes = new LinkedHashMap<>();
            Set<ContainerTarget> toCheck = new LinkedHashSet<>();
            for (String key : candidates.keySet()) {
                List<ContainerTarget> targets = context.workspace().resolvedStorageTargets(key);
                if ("UNCLASSIFIABLE".equals(remainderKind)) {
                    routes.put(key, targets);
                    continue;
                }
                if (targets.isEmpty() || source != null && targets.stream()
                        .anyMatch(target -> sameContainer(source, target))) continue;
                routes.put(key, targets);
                for (ContainerTarget target : targets) {
                    toCheck.add(target);
                    toCheck.addAll(partialStorage.computeIfAbsent(target, ignored ->
                            target.storagePolicy().mode() == StorageMode.NORMAL ? List.of()
                                    : partialBoxContainers(context, target.storagePolicy(),
                                            configuration)));
                }
            }
            toCheck.removeAll(checkedStorage.keySet());
            readStorageCapacity(toCheck.iterator(), () -> {
                eligibleItems = dev.stowling.automation.ClassificationRemainderPlan.select(routes,
                        "UNCLASSIFIABLE".equals(remainderKind),
                        (target, key) -> storageIsFull(target, key,
                                candidates.get(key).getMaxStackSize()));
                after.run();
            });
        }

        private void readStorageCapacity(java.util.Iterator<ContainerTarget> pending,
                Runnable after) {
            if (!pending.hasNext()) {
                after.run();
                return;
            }
            ContainerTarget target = pending.next();
            read(target, contents -> {
                checkedStorage.put(target, contents);
                readStorageCapacity(pending, after);
            }, () -> {
                checkedStorage.put(target, List.of());
                readStorageCapacity(pending, after);
            });
        }

        private boolean storageIsFull(ContainerTarget target, String key, int stackSize) {
            List<ItemStack> contents = checkedStorage.get(target);
            StoragePolicy policy = target.storagePolicy();
            List<ContainerTarget> partialTargets = partialStorage.get(target);
            if (contents.isEmpty() || policy.mode() != StorageMode.NORMAL
                    && policy.partialBoxDestinationRef() != null && partialTargets.isEmpty()) {
                return false;
            }
            boolean boxHasSpace = policy.mode() != StorageMode.NORMAL
                    && containsFittingBox(contents, key, stackSize);
            for (ContainerTarget partial : partialTargets) {
                List<ItemStack> stock = checkedStorage.get(partial);
                if (stock.isEmpty() || stock.stream().anyMatch(ItemStack::isEmpty)
                        || containsFittingBox(stock, key, stackSize)) return false;
            }
            return !ClassificationOverflowCapacity.hasSpace(policy, transferSlots(contents),
                    contents.size(), key, stackSize, boxHasSpace);
        }

        private boolean containsFittingBox(List<ItemStack> contents, String key, int stackSize) {
            return contents.stream().filter(MinecraftItemIdentity::isBox)
                    .filter(stack -> ItemIdentity.base(key).equals(
                            MinecraftItemIdentity.boxCategory(stack)))
                    .anyMatch(stack -> !ShulkerTransferPlanner.fitting(boxSlots(stack),
                            StoragePackingPlanner.BOX_SLOTS, Map.of(key, 1),
                            Map.of(key, stackSize)).isEmpty());
        }

        private boolean eligibleBox(ItemStack stack) {
            return MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream()
                    .map(MinecraftItemIdentity::key).allMatch(eligibleItems::contains);
        }

        private void drainVerifiedSource(List<ItemStack> contents) {
            Set<String> keys = new LinkedHashSet<>();
            ItemStack mixed = ItemStack.EMPTY;
            for (ItemStack stack : contents) {
                if (stack.isEmpty()) continue;
                String key = MinecraftItemIdentity.key(stack);
                if (!ItemIdentity.isBox(key) ? eligibleItems.contains(key)
                        : !isEmptyBox(stack) && eligibleBox(stack)) {
                    keys.add(ItemIdentity.exact(key));
                } else if (mixed.isEmpty() && ItemIdentity.isBox(key) && !isEmptyBox(stack)
                        && MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream()
                                .map(MinecraftItemIdentity::key).anyMatch(eligibleItems::contains)) {
                    mixed = stack;
                }
            }
            if (!keys.isEmpty()) {
                prepareWarehouse();
                var drain = new ContainerBatchWithdrawal(context, currentSource, keys);
                steps.add(new Step(drain, () -> {
                    if (drain.withdrawn().isEmpty()) {
                        throw new IllegalStateException("背包空间不足，缓存溢出物品尚未取出");
                    }
                    packOwned();
                }));
            } else if (!mixed.isEmpty()) {
                prepareWarehouse();
                splitCacheBox(mixed);
            } else {
                currentSource = null;
                visitSource();
            }
        }

        private void splitCacheBox(ItemStack mixed) {
            String key = MinecraftItemIdentity.key(mixed);
            int before = owned().getOrDefault(key, 0);
            transfer(currentSource, Map.of(key, 1), true, () -> {
                if (owned().getOrDefault(key, 0) <= before) {
                    throw new IllegalStateException("未能取出含有溢出物品的潜影盒");
                }
                box = mixed;
                Set<String> selectors = MinecraftItemIdentity.contents(mixed)
                        .nonEmptyItemCopyStream().map(MinecraftItemIdentity::key)
                        .filter(eligibleItems::contains).map(ItemIdentity::exact)
                        .collect(java.util.stream.Collectors.toSet());
                unpackOverflowBox(selectors, drain -> {
                    String remainder = MinecraftItemIdentity.key(box);
                    int remaining = owned().getOrDefault(remainder, 0);
                    transfer(currentSource, Map.of(remainder, 1), false, () -> {
                        if (owned().getOrDefault(remainder, 0) >= remaining) {
                            throw new IllegalStateException("混装盒未能归还原缓存，等待收尾");
                        }
                        if (drain.withdrawn().isEmpty()) {
                            throw new IllegalStateException("未能从混装盒取出溢出物品");
                        }
                        packOwned();
                    });
                });
            });
        }

        @Override
        String completionMessage() {
            if (skipMessage != null) return skipMessage;
            if (remainderKind != null) {
                String label = "UNCLASSIFIABLE".equals(remainderKind) ? "不可分类" : "溢出";
                return label + "物品存放结束，未能存入的物品保留在物品来源";
            }
            if (boxedOnly) return storedOrdinary ? "储存区溢出物品已装盒存入机器人仓库"
                    : "没有确认需要存入机器人仓库的储存区溢出物品";
            if (unstackableFull && storedUnstackable == 0) {
                return "不可堆叠溢出区已满，剩余物品已打包送往普通溢出";
            }
            if (storedUnstackable > 0) {
                String stored = "已送往不可堆叠溢出区 " + storedUnstackable + " 件";
                return storedOrdinary ? stored + "，其余已打包送往普通溢出" : stored;
            }
            return storedOrdinary ? "分类目标装不下的物品已打包送往溢出设施"
                    : "没有确认需要打包的分类溢出物品";
        }
    }

    private final class EmptyBoxSettlement extends Session {
        private List<ContainerTarget> caches;
        private int limit;

        EmptyBoxSettlement(TaskExecutionContext context) {
            super(context);
        }

        @Override
        void initialize() {
            var policy = context.workspace().cleanupPolicy().emptyBoxWarehouse();
            if (policy == null || !policy.enabled()) return;
            limit = policy.retainedLimit();
            caches = classificationCaches(context);
            var warehouseTargets = emptyBoxWarehouse(context);
            if (caches.stream().anyMatch(cache -> warehouseTargets.stream()
                    .anyMatch(target -> sameContainer(cache, target)))) {
                throw new IllegalStateException("空盒仓库不能与物品来源重叠，请调整区域");
            }
            inspectCaches(0);
        }

        private void inspectCaches(int index) {
            if (index >= caches.size()) {
                returnEmptyBoxes(() -> settleCache(0, limit));
                return;
            }
            var cache = caches.get(index);
            read(cache, contents -> {
                recordCacheContents(context, cache, contents);
                inspectCaches(index + 1);
            });
        }

        private void settleCache(int index, int remainingKeep) {
            if (index >= caches.size()) return;
            var cache = caches.get(index);
            read(cache, contents -> {
                var snapshot = recordCacheContents(context, cache, contents);
                int count = EmptyBoxSupplyPlan.emptyCount(snapshot.counts());
                int keep = Math.min(remainingKeep, count);
                if (count <= keep) {
                    settleCache(index + 1, remainingKeep - keep);
                    return;
                }
                int capacity = (int) minecraft.player.getInventory().getNonEquipmentItems().stream()
                        .filter(ItemStack::isEmpty).count();
                if (capacity == 0) {
                    throw new IllegalStateException("背包没有空位搬运缓存空盒，请腾出空间后重试");
                }
                var access = MinecraftStorageAccess.collect(minecraft, context, cache, current ->
                        EmptyBoxSupplyPlan.surplus(emptyBoxCounts(current),
                                keep, capacity));
                int before = count;
                steps.add(new Step(access, () -> {
                    int after = EmptyBoxSupplyPlan.emptyCount(
                            emptyBoxCounts(access.contents()));
                    if (after >= before) {
                        throw new IllegalStateException("缓存空盒取出未确认，停止归还");
                    }
                    returnEmptyBoxes(() -> settleCache(index, remainingKeep));
                }));
            });
        }

        @Override
        String completionMessage() {
            return "缓存多余空盒已归还空盒仓库";
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
            recover(() -> steps.add(new Step(new CleanupInventorySettlement(context.inventory(),
                    context.workspace().inventoryAccount()), this::resolveDestinations)));
        }

        private void resolveDestinations() {
            if (context.workspace().cleanupPolicy().emptyBoxWarehouse() != null
                    && owned().keySet().stream().anyMatch(ItemIdentity::isEmptyBox)) {
                returnEmptyBoxes(this::resolveDestinations);
                return;
            }
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
            if (owned().isEmpty()) {
                return;
            }
            if (index >= targets.size()) {
                if (overflowEnabled && !overflowAttempted) {
                    overflowAttempted = true;
                    steps.add(new Step(new Overflow(context, null, false, null),
                            () -> depositAll(index)));
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
        var hazards = MinecraftPickupSafety.hazards(minecraft.player, new AABB(minX, minY, minZ,
                maxX + 1.0, maxY + 2.0, maxZ + 1.0).inflate(3));
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockPosition candidate = new BlockPosition(x, y, z);
                    if (!reserved(context, candidate)
                            && MinecraftPickupSafety.safePackingSite(candidate, hazards)
                            && MinecraftShulkerAction.validSite(minecraft, position(candidate))) {
                        candidates.add(candidate);
                    }
                }
            }
        }
        if (candidates.isEmpty() && configuration.fallbackPackingPosition() != null) {
            BlockPosition fallback = configuration.fallbackPackingPosition();
            if (!reserved(context, fallback)
                    && MinecraftPickupSafety.safePackingSite(fallback,
                            MinecraftPickupSafety.hazards(minecraft.player, fallback))
                    && MinecraftShulkerAction.validSite(minecraft, position(fallback))) {
                return fallback;
            }
        }
        return candidates.stream().min(Comparator.comparingDouble(candidate ->
                distance(candidate) + squared(candidate, target.position())))
                .orElseThrow(() -> new IllegalStateException("附近没有可用打包位置，请指定单片打包区域"
                        + "；目标=" + target.position() + "；打包区域=" + allowed
                        + "。需要避开掉落物拾取范围，留出放盒和开盖空间，"
                        + "且下方为不可右键交互的坚固方块"));
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
