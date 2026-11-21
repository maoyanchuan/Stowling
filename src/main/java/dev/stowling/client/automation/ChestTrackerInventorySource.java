package dev.stowling.client.automation;

import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import dev.stowling.inventory.RecordedContainerCapacity;
import dev.stowling.inventory.WarehouseInventory;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.config.StorageSettingsStore;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import red.jackf.chesttracker.api.memory.CommonKeys;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBankAccess;

/** Reads the active bank without changing Chest Tracker's settings or storage. */
public final class ChestTrackerInventorySource implements Supplier<WarehouseInventory> {
    private final Minecraft minecraft;
    private final ClientActionScheduler scheduler;
    private final ConfigStore configs;
    private final StorageSettingsStore storageSettings;
    private Object activeBank;
    private final Map<String, CachedContainer> cached = new LinkedHashMap<>();

    public ChestTrackerInventorySource(Minecraft minecraft, ClientActionScheduler scheduler,
            ConfigStore configs, StorageSettingsStore storageSettings) {
        this.minecraft = minecraft;
        this.scheduler = scheduler;
        this.configs = configs;
        this.storageSettings = storageSettings;
    }

    @Override
    public WarehouseInventory get() {
        return get(false);
    }

    public WarehouseInventory getForDisplay() {
        return get(true);
    }

    private WarehouseInventory get(boolean excludeMarkers) {
        try {
            return scheduler.submit(() -> read(excludeMarkers)).get(8, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("库存读取已中断", exception);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                exception) {
            throw new IllegalStateException("读取 Chest Tracker 库存失败，请确认客户端仍在响应",
                    exception);
        }
    }

    private WarehouseInventory read(boolean excludeMarkers) {
        if (minecraft.level == null || minecraft.player == null) {
            cached.clear();
            activeBank = null;
            return snapshot("DISCONNECTED", "请先进入世界，库存仅读取当前连接的记忆库。", "",
                    List.of(), excludeMarkers);
        }
        String dimension = minecraft.level.dimension().identifier().toString();
        var loaded = MemoryBankAccess.INSTANCE.getLoaded();
        if (loaded.isEmpty()) {
            cached.clear();
            activeBank = null;
            return snapshot("NO_MEMORY_BANK", "Chest Tracker 尚未加载记忆库，请先打开一个箱子。",
                    dimension, List.of(), excludeMarkers);
        }
        List<WarehouseInventory.Container> containers = new ArrayList<>();
        if (activeBank != loaded.orElseThrow()) {
            cached.clear();
            activeBank = loaded.orElseThrow();
        }
        Set<String> seen = new HashSet<>();
        List<AreaBounds> warehouseAreas = storageSettings.overflowRegions().stream()
                .map(region -> new AreaBounds(region.firstX(), region.firstY(), region.firstZ(),
                        region.secondX(), region.secondY(), region.secondZ())).toList();
        for (var bankEntry : loaded.orElseThrow().getAllMemories().entrySet()) {
            var key = bankEntry.getKey();
            if (key.equals(CommonKeys.ENDER_CHEST_KEY) || key.equals(CommonKeys.SHARE_ENDER_CHEST)) {
                continue;
            }
            String dimensionId = key.toString();
            var warehousePoints = Set.copyOf(storageSettings.overflowPositions(dimensionId));
            for (var entry : bankEntry.getValue().getMemories().entrySet()) {
                Memory memory = entry.getValue();
                if (memory.entityUuid() != null) {
                    continue;
                }
                List<BlockPosition> positions = new ArrayList<>();
                positions.add(position(entry.getKey()));
                memory.otherPositions().forEach(point -> positions.add(position(point)));
                if (positions.stream().anyMatch(point -> warehousePoints.contains(point)
                        || warehouseAreas.stream().anyMatch(area -> area.contains(point)))) {
                    continue;
                }
                if (positions.stream().anyMatch(point ->
                        seen.contains(dimensionId + ":" + point))) {
                    continue;
                }
                positions.forEach(point -> seen.add(dimensionId + ":" + point));
                String id = dimensionId + ":" + positions.getFirst();
                CachedContainer existing = cached.get(id);
                if (existing == null || existing.memory() != memory
                        || !java.util.Objects.equals(existing.timestamp(), memory.realTimestamp())) {
                    String blockId = memory.container().map(block ->
                            BuiltInRegistries.BLOCK.getKey(block).toString()).orElse("");
                    existing = new CachedContainer(memory, memory.realTimestamp(), blockId,
                            container(dimensionId, positions, memory, blockId));
                    cached.put(id, existing);
                }
                containers.add(existing.container());
            }
        }
        containers.sort(Comparator.comparing(WarehouseInventory.Container::id));
        Set<String> retained = containers.stream().map(WarehouseInventory.Container::id)
                .collect(java.util.stream.Collectors.toSet());
        cached.keySet().retainAll(retained);
        return snapshot("READY", "Chest Tracker 最近记录；未打开的容器与外部改动需重新核对。",
                dimension, containers, excludeMarkers);
    }

    private WarehouseInventory snapshot(String state, String message, String dimension,
            List<WarehouseInventory.Container> containers, boolean excludeMarkers) {
        var config = configs.read().config();
        List<AreaBounds> markerAreas = excludeMarkers
                ? markerAreas(config.categoryZones()) : List.of();
        if (!markerAreas.isEmpty()) {
            containers = containers.stream().filter(stock -> !isMarkerContainer(
                    stock, cached.get(stock.id()).blockId(), dimension, markerAreas)).toList();
        }
        List<WarehouseInventory.Area> areas = new ArrayList<>();
        for (var region : config.regions()) {
            areas.add(new WarehouseInventory.Area("region:" + region.id(), "区域 · " + region.name(),
                    dimension, List.of(new AreaBounds(region.firstX(), region.firstY(),
                            region.firstZ(), region.secondX(), region.secondY(), region.secondZ()))));
        }
        for (var zone : config.categoryZones()) {
            areas.add(new WarehouseInventory.Area("category-zone:" + zone.id(),
                    "仓储布局 · " + zone.name(), dimension,
                    zone.bindings().stream().map(binding -> binding.storageArea()).toList()));
        }
        return new WarehouseInventory(state, message, dimension, Instant.now(), containers, areas);
    }

    static List<AreaBounds> markerAreas(List<CategoryZoneDefinition> zones) {
        return zones.stream().flatMap(zone -> zone.bindings().stream())
                .filter(binding -> ContainerContentsCategoryRule.ID.equals(binding.ruleId()))
                .map(CategoryBindingDefinition::markerArea)
                .toList();
    }

    static boolean isMarkerContainer(WarehouseInventory.Container stock, String blockId,
            String dimension, List<AreaBounds> markerAreas) {
        return dimension.equals(stock.dimension())
                && ContainerContentsCategoryRule.CONTAINER_TYPES.contains(blockId)
                && stock.positions().stream().anyMatch(position ->
                        markerAreas.stream().anyMatch(area -> area.contains(position)));
    }

    private static WarehouseInventory.Container container(String dimension,
            List<BlockPosition> positions, Memory memory, String blockId) {
        Map<String, WarehouseInventory.Entry> items = new LinkedHashMap<>();
        List<WarehouseInventory.Box> boxes = new ArrayList<>();
        int full = 0;
        int partial = 0;
        int empty = 0;
        int occupied = 0;
        for (ItemStack stack : memory.fullItems()) {
            if (stack.isEmpty()) continue;
            occupied++;
            if (MinecraftItemIdentity.isBox(stack)) {
                var content = MinecraftItemIdentity.contents(stack).nonEmptyItemCopyStream().toList();
                if (content.isEmpty()) {
                    empty += stack.getCount();
                    add(items, stack, false, 1);
                } else {
                    if (MinecraftItemIdentity.full(stack)) full += stack.getCount();
                    else partial += stack.getCount();
                    content.forEach(inner -> add(items, inner, true, stack.getCount()));
                    List<WarehouseInventory.Entry> boxItems = content.stream().map(inner ->
                            new WarehouseInventory.Entry(MinecraftItemIdentity.key(inner),
                                    MinecraftItemIdentity.base(inner),
                                    inner.getHoverName().getString(), inner.getCount(), 0,
                                    inner.getMaxStackSize(),
                                    MinecraftItemIdentity.iconModel(inner))).toList();
                    for (int index = 0; index < stack.getCount(); index++) {
                        boxes.add(new WarehouseInventory.Box(MinecraftItemIdentity.boxCategory(stack),
                                content.size(), boxItems));
                    }
                }
            } else {
                add(items, stack, false, 1);
            }
        }
        var name = memory.renderName();
        Instant recorded = memory.realTimestamp();
        int slots = RecordedContainerCapacity.slots(blockId, positions.size());
        return new WarehouseInventory.Container(dimension + ":" + positions.getFirst(),
                name == null ? "已记录容器" : name.getString(), dimension, positions,
                recorded == null || recorded.equals(Instant.EPOCH) ? null : recorded,
                slots, occupied, full, partial, empty,
                List.copyOf(items.values()), boxes);
    }

    private static void add(Map<String, WarehouseInventory.Entry> items, ItemStack stack,
            boolean boxed, int multiplier) {
        String key = MinecraftItemIdentity.key(stack);
        long count = Math.multiplyExact((long) stack.getCount(), multiplier);
        var previous = items.get(key);
        long loose = previous == null ? 0 : previous.loose();
        long packed = previous == null ? 0 : previous.boxed();
        items.put(key, new WarehouseInventory.Entry(key, MinecraftItemIdentity.base(stack),
                stack.getHoverName().getString(), loose + (boxed ? 0 : count),
                packed + (boxed ? count : 0), stack.getMaxStackSize(),
                MinecraftItemIdentity.iconModel(stack)));
    }

    private static BlockPosition position(net.minecraft.core.BlockPos position) {
        return new BlockPosition(position.getX(), position.getY(), position.getZ());
    }

    private record CachedContainer(Memory memory, Instant timestamp, String blockId,
            WarehouseInventory.Container container) {
    }
}
