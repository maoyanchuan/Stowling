package dev.stowling.client.automation;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerVisitPlan;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.StorageSettingsStore;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.StorageAutomationSettings;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
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
    private final Map<UUID, MinecraftShulkerAction.Resource> resources = new LinkedHashMap<>();

    public MinecraftStorageAutomation(Minecraft minecraft, StorageSettingsStore settings) {
        this(minecraft, settings, (region, types, volume) ->
                dev.stowling.automation.ContainerScanResult.failure("区域扫描组件不可用"));
    }

    public MinecraftStorageAutomation(Minecraft minecraft, StorageSettingsStore settings,
            dev.stowling.automation.ContainerRegionPort containerRegions) {
        this.minecraft = minecraft;
        this.settings = settings;
        this.containerRegions = containerRegions;
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
    public TaskModuleExecutor cleanup(TaskExecutionContext context) {
        return new Cleanup(context);
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
                        terminal = TaskModuleResult.succeeded("储存操作完成");
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

    private final class Packing extends Session {
        private final ContainerTarget target;
        private final StoragePolicy policy;
        private final Deque<String> categories;
        private String category;
        private int stackSize;
        private int looseLimit;
        private List<ItemStack> targetContents = List.of();
        private final Map<ContainerTarget, List<ItemStack>> sourceContents = new LinkedHashMap<>();
        private ItemStack box;
        private ContainerTarget boxTarget;
        private boolean requestedBox;
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
                inspectTarget();
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
                if (policy.mode() == dev.stowling.v2.model.StorageMode.MIXED) {
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
                if (loose > looseLimit && !carried.isEmpty()) {
                    ContainerTarget buffer = context.workspace().selectedContainer()
                            .orElseGet(() -> new ContainerTarget(
                                    temporaryContainers().getFirst().position(), "container"));
                    int before = sum(carried);
                    transfer(buffer, carried, false, () -> {
                        if (sum(rawOwned()) == before) {
                            throw new IllegalStateException("整理目标箱前暂存空间不足");
                        }
                        context.workspace().setCacheHasMore(true);
                        inspectTarget();
                    });
                } else if (amount > 0) {
                    int before = sum(carried);
                    transfer(target, take(carried, amount), false, () -> {
                        if (sum(rawOwned()) == before) {
                            defer();
                        } else {
                            inspectTarget();
                        }
                    });
                } else if (carried.isEmpty() && loose <= looseLimit && boxes.isEmpty()) {
                    nextCategory();
                } else {
                    preferredSite = chooseSite(context, target, policy.packingArea(), configuration);
                    sourceContents.clear();
                    scanSources(new ContainerVisitPlan(sourceTargets()));
                }
            });
        }

        private void scanSources(ContainerVisitPlan plan) {
            BlockPos feet = minecraft.player.blockPosition();
            ContainerTarget source = plan.next(new BlockPosition(feet.getX(), feet.getY(),
                    feet.getZ())).orElse(null);
            if (source == null) {
                chooseBox();
                return;
            }
            read(source, contents -> {
                sourceContents.put(source, contents);
                scanSources(plan);
            });
        }

        private void chooseBox() {
            box = inventoryStacks().stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                    && category.equals(MinecraftItemIdentity.boxCategory(stack))
                    && !MinecraftItemIdentity.full(stack)
                    && owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                    .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
            requestedBox = !box.isEmpty();
            if (box.isEmpty()) {
                box = targetContents.stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                        && category.equals(MinecraftItemIdentity.boxCategory(stack))
                        && !MinecraftItemIdentity.full(stack))
                        .max(Comparator.comparingInt(MinecraftStorageAutomation::insideCount))
                        .map(ItemStack::copy).orElse(ItemStack.EMPTY);
            }
            int available = sum(rawOwned()) + Math.max(0, rawCount(targetContents) - looseLimit)
                    + sourceContents.values().stream().mapToInt(this::rawCount).sum();
            int inside = box.isEmpty() ? 0 : insideCount(box);
            if (!StoragePackingPlanner.canPack(policy.allowPartialBoxes(), available, inside, stackSize)) {
                if (requestedBox && policy.allowPartialBoxes()) {
                    storeBox();
                } else {
                    defer();
                }
                return;
            }
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
                obtainEmpty(0);
            }
        }

        private void obtainEmpty(int index) {
            box = inventoryStacks().stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                    && MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream().findAny().isEmpty()
                    && owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                    .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
            if (!box.isEmpty()) {
                placeBox();
                return;
            }
            if (index >= configuration.emptyBoxContainers().size()) {
                throw new IllegalStateException("空潜影盒供应不足，物品将返回暂存箱");
            }
            ContainerTarget supply = new ContainerTarget(configuration.emptyBoxContainers().get(index),
                    "container");
            read(supply, contents -> {
                box = contents.stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                        && MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream().findAny().isEmpty())
                        .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
                if (box.isEmpty()) {
                    obtainEmpty(index + 1);
                } else {
                    String key = MinecraftItemIdentity.key(box);
                    transfer(supply, Map.of(key, 1), true, () -> {
                        requireOwned(key);
                        placeBox();
                    });
                }
            });
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
                int remaining = 27 * stackSize - inside;
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
                } else if (remaining <= 0) {
                    finishBox();
                } else {
                    fetchMore(remaining, 0);
                }
            });
        }

        private void fetchMore(int wanted, int index) {
            List<ContainerTarget> sources = new ArrayList<>();
            sources.add(target);
            sources.addAll(sourceTargets());
            if (index == sources.size()) {
                finishBox();
                return;
            }
            ContainerTarget source = sources.get(index);
            read(source, contents -> {
                Map<String, Integer> raw = rawItems(contents);
                int amount = source.equals(target)
                        ? Math.max(0, sum(raw) - looseLimit) : sum(raw);
                if (amount == 0) {
                    fetchMore(wanted, index + 1);
                } else {
                    int before = sum(rawOwned());
                    transfer(source, take(raw, Math.min(amount, wanted)), true, () -> {
                        if (sum(rawOwned()) == before) {
                            fetchMore(wanted, index + 1);
                        } else {
                            fillBox();
                        }
                    });
                }
            });
        }

        private void finishBox() {
            recover(() -> {
                box = inventoryStacks().stream().filter(stack -> MinecraftItemIdentity.isBox(stack)
                        && category.equals(MinecraftItemIdentity.boxCategory(stack))
                        && owned().getOrDefault(MinecraftItemIdentity.key(stack), 0) > 0)
                        .findFirst().map(ItemStack::copy).orElseThrow(() ->
                                new IllegalStateException("回收后找不到本次打包的潜影盒"));
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
                inspectTarget();
            });
        }

        private void defer() {
            context.workspace().markTargetUnavailable(target);
            context.workspace().setCacheHasMore(false);
            nextCategory();
        }

        private List<ContainerTarget> sourceTargets() {
            Set<ContainerTarget> sources = new LinkedHashSet<>(context.workspace().cacheContainers());
            sources.addAll(temporaryContainers());
            return sources.stream().filter(source -> !source.position().equals(target.position()))
                    .filter(source -> !configuration.emptyBoxContainers().contains(source.position()))
                    .distinct().sorted(Comparator.comparingDouble(source -> distance(source.position())))
                    .toList();
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
