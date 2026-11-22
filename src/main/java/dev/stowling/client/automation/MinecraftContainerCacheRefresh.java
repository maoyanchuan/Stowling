package dev.stowling.client.automation;

import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.ContainerCacheRefresh;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.runtime.TaskTrace;
import dev.stowling.v2.runtime.modules.TaskExecutionContext;
import dev.stowling.v2.runtime.modules.TaskModuleExecutor;
import dev.stowling.v2.runtime.modules.TaskModuleResult;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import red.jackf.chesttracker.api.memory.Memory;
import red.jackf.chesttracker.api.memory.MemoryBankAccess;

final class MinecraftContainerCacheRefresh implements TaskModuleExecutor {
    private static final int CHUNK_TIMEOUT_TICKS = 2400;
    private static final int MEMORY_WAIT_TICKS = 10;

    private final RefreshWorld world;
    private final TaskExecutionContext context;
    private final ContainerCacheRefresh options;
    private final Map<UUID, Map<BlockPosition, Set<String>>> markerReads = new LinkedHashMap<>();
    private final Map<UUID, Set<BlockPosition>> observedMarkers = new LinkedHashMap<>();
    private final Map<BlockPosition, Boolean> visibility = new LinkedHashMap<>();
    private final Set<BlockPosition> markerPositions = new LinkedHashSet<>();
    private final Set<BlockPosition> visited = new HashSet<>();
    private final Deque<RegionDefinition> chunks = new ArrayDeque<>();
    private final Deque<ContainerTarget> containers = new ArrayDeque<>();

    private List<CategoryBindingDefinition> bindings = List.of();
    private List<CategoryBindingDefinition> markerBindings = List.of();
    private List<AreaBounds> storageAreas = List.of();
    private List<AreaBounds> markerAreas = List.of();
    private List<AreaBounds> scanAreas;
    private String scope;
    private BlockPosition travelTarget;
    private int travelTicks;
    private ContainerTarget selected;
    private boolean inspecting;
    private boolean needsInventory;
    private boolean markerOnlyTarget;
    private Object previousMemory;
    private Set<String> lastItems;
    private int memoryTicks;
    private int succeeded;
    private int failures;
    private boolean missingChunks;
    private String firstFailure;
    private TaskModuleResult terminal;
    private boolean stopRequested;

    MinecraftContainerCacheRefresh(Minecraft minecraft, MinecraftContainerRegionScanner scanner,
            TaskExecutionContext context, ContainerCacheRefresh options) {
        this(context, options, new ClientWorld(minecraft, scanner, minecraft.level));
    }

    MinecraftContainerCacheRefresh(TaskExecutionContext context, ContainerCacheRefresh options,
            RefreshWorld world) {
        this.world = world;
        this.context = context;
        this.options = options;
    }

    @Override
    public TaskModuleResult start() {
        if (!world.available()) {
            return TaskModuleResult.failed("进入游戏世界后才能刷新容器缓存");
        }
        scope = world.scope();
        if (options.includeMarkersInInventory() && scope == null) {
            return TaskModuleResult.failed("无法识别当前世界，不能保存识别区库存设置");
        }
        String name = selectAreas();
        if (scanAreas.isEmpty()) {
            return TaskModuleResult.succeeded("选区没有需要刷新的容器");
        }
        var player = world.position();
        scanAreas.stream().flatMap(area -> ContainerCacheRefreshPlan.chunks(
                        options.target().uuid(), name, area).stream())
                .distinct()
                .sorted(Comparator.comparingDouble(chunk -> {
                    var center = ContainerCacheRefreshPlan.chunkCenter(chunk);
                    return Math.hypot((long) center.x() - player.x(),
                            (long) center.z() - player.z());
                }))
                .forEach(chunks::addLast);
        for (var binding : markerBindings) {
            var cached = context.categoryMarkerCache().contents(binding.id()).orElse(Map.of());
            markerReads.put(binding.id(), new LinkedHashMap<>(cached));
            observedMarkers.put(binding.id(), new HashSet<>());
        }
        return TaskModuleResult.running("准备扫描选区「" + name + "」");
    }

    private String selectAreas() {
        UUID id = options.target().uuid();
        String name;
        if (options.target().type() == ResourceType.REGION) {
            RegionDefinition region = context.regions().get(id);
            storageAreas = List.of(bounds(region));
            name = region.name();
        } else {
            var zone = context.categoryZones().get(id);
            bindings = zone.bindings();
            storageAreas = bindings.stream().map(CategoryBindingDefinition::storageArea)
                    .distinct().toList();
            if (options.scanMarkers() || options.markerOnly()) {
                markerBindings = bindings.stream().filter(binding ->
                        ContainerContentsCategoryRule.ID.equals(binding.ruleId())).toList();
                markerAreas = (options.markerOnly() ? markerBindings : bindings).stream()
                        .map(CategoryBindingDefinition::markerArea).distinct().toList();
            }
            name = zone.name();
        }
        Set<AreaBounds> areas = new LinkedHashSet<>();
        if (!options.markerOnly()) {
            areas.addAll(storageAreas);
        }
        areas.addAll(markerAreas);
        scanAreas = List.copyOf(areas);
        return name;
    }

    @Override
    public TaskModuleResult tick() {
        if (stopRequested && terminal == null) {
            cancel();
        }
        if (terminal != null) {
            return terminal;
        }
        if (!world.available()) {
            cancel();
            return TaskModuleResult.failed("游戏世界已断开或切换，刷新已结束");
        }
        if (inspecting) {
            return handleInspection(context.containerInspections().pollInspection());
        }
        if (selected != null) {
            return confirmMemory();
        }
        if (!containers.isEmpty()) {
            return openNext();
        }
        if (!chunks.isEmpty()) {
            return scanNextChunk();
        }
        return finish();
    }

    private TaskModuleResult scanNextChunk() {
        RegionDefinition chunk = chunks.getFirst();
        BlockPosition center = ContainerCacheRefreshPlan.chunkCenter(chunk);
        if (!world.chunkLoaded(center)) {
            return loadChunk(center);
        }
        stopTravel();
        chunks.removeFirst();
        var scan = world.scan(chunk, options);
        if (!scan.succeeded()) {
            missingChunks = true;
            reportFailure(center, scan.message());
        } else {
            for (ContainerTarget target : scan.containers()) {
                collectTarget(target);
            }
        }
        return TaskModuleResult.running("已刷新 " + succeeded + " 个容器，待扫描 "
                + chunks.size() + " 片区域");
    }

    private TaskModuleResult loadChunk(BlockPosition center) {
        if (travelTarget == null) {
            travelTarget = center;
            travelTicks = 0;
            if (context.navigation().startHorizontal(center) == NavigationStatus.FAILED) {
                return skipChunk(center);
            }
        } else {
            NavigationStatus status = context.navigation().status(center);
            if (status == NavigationStatus.FAILED || ++travelTicks > CHUNK_TIMEOUT_TICKS) {
                return skipChunk(center);
            }
        }
        return TaskModuleResult.waiting("正在前往未加载区块 " + center.x() + ", " + center.z());
    }

    private TaskModuleResult skipChunk(BlockPosition center) {
        stopTravel();
        chunks.removeFirst();
        missingChunks = true;
        reportFailure(center, "区块无法加载");
        return TaskModuleResult.running("已跳过无法加载的区块");
    }

    private void stopTravel() {
        if (travelTarget != null) {
            context.navigation().cancel();
            travelTarget = null;
        }
    }

    private void collectTarget(ContainerTarget found) {
        for (var binding : matchingMarkerBindings(found)) {
            found.entrances().stream().filter(binding.markerArea()::contains)
                    .forEach(observedMarkers.get(binding.id())::add);
        }
        if (!MinecraftContainerRegionScanner.enabledRefreshType(found.type(), options)
                || !ContainerCacheRefreshPlan.markVisited(visited, found)) {
            return;
        }
        ContainerTarget target = ContainerCacheRefreshPlan.preferInside(found, scanAreas);
        for (var binding : bindings) {
            if (!options.markerOnly() && touches(target, List.of(binding.storageArea()))
                    && binding.standingArea() != null) {
                target = target.withStandingArea(binding.standingArea(), binding.storagePolicy());
                break;
            }
        }
        containers.addLast(target);
    }

    private TaskModuleResult openNext() {
        selected = containers.removeFirst();
        boolean storage = touches(selected, storageAreas);
        markerOnlyTarget = !storage && touches(selected, markerAreas);
        needsInventory = !options.markerOnly()
                && (storage || markerOnlyTarget && options.includeMarkersInInventory());
        previousMemory = needsInventory ? world.memoryStamp(selected) : null;
        inspecting = true;
        return handleInspection(context.containerInspections().startInspection(selected));
    }

    private TaskModuleResult handleInspection(ContainerInspectionResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting("已刷新 " + succeeded + " 个 · " + result.message());
        }
        inspecting = false;
        if (result.state() == ContainerTransferState.FAILED) {
            reportFailure(selected.position(), result.message());
            selected = null;
            return TaskModuleResult.running("开箱失败，继续刷新");
        }
        lastItems = result.itemIds();
        if (markerOnlyTarget) {
            markerPositions.addAll(selected.entrances());
        }
        for (var binding : matchingMarkerBindings(selected)) {
            var entries = markerReads.get(binding.id());
            // A double chest contributes one copy of the contents per binding.
            for (BlockPosition entrance : selected.entrances()) {
                entries.remove(entrance);
            }
            markerPosition(selected, binding).ifPresent(position -> entries.put(position, lastItems));
        }
        if (markerOnlyTarget && !options.markerOnly() && !options.includeMarkersInInventory()) {
            selected.entrances().forEach(position -> visibility.put(position, false));
        }
        if (!needsInventory) {
            return confirmRead();
        }
        memoryTicks = 0;
        return confirmMemory();
    }

    private TaskModuleResult confirmMemory() {
        Object current = world.memoryStamp(selected);
        if (current == null && lastItems.isEmpty()
                || current != null && !current.equals(previousMemory)) {
            if (markerOnlyTarget) {
                selected.entrances().forEach(position -> visibility.put(position, true));
            }
            return confirmRead();
        }
        if (memoryTicks++ < MEMORY_WAIT_TICKS) {
            return TaskModuleResult.waiting("等待 Chest Tracker 更新容器记录");
        }
        reportFailure(selected.position(), "Chest Tracker 未更新容器记录");
        selected = null;
        return TaskModuleResult.running("库存记录未更新，继续刷新");
    }

    private TaskModuleResult confirmRead() {
        succeeded++;
        selected = null;
        return TaskModuleResult.running("已刷新 " + succeeded + " 个容器");
    }

    private List<CategoryBindingDefinition> matchingMarkerBindings(ContainerTarget target) {
        if (!ContainerContentsCategoryRule.CONTAINER_TYPES.contains(target.type())) {
            return List.of();
        }
        return markerBindings.stream()
                .filter(binding -> markerPosition(target, binding).isPresent()).toList();
    }

    private static java.util.Optional<BlockPosition> markerPosition(ContainerTarget target,
            CategoryBindingDefinition binding) {
        return target.entrances().stream().filter(binding.markerArea()::contains)
                .min(Comparator.comparingInt(BlockPosition::x).thenComparingInt(BlockPosition::y)
                        .thenComparingInt(BlockPosition::z));
    }

    private static boolean touches(ContainerTarget target, List<AreaBounds> areas) {
        return target.entrances().stream().anyMatch(position ->
                areas.stream().anyMatch(area -> area.contains(position)));
    }

    private static AreaBounds bounds(RegionDefinition region) {
        return new AreaBounds(region.firstX(), region.firstY(), region.firstZ(),
                region.secondX(), region.secondY(), region.secondZ());
    }

    private void reportFailure(BlockPosition position, String message) {
        failures++;
        String detail = position + " · " + message;
        if (firstFailure == null) {
            firstFailure = detail;
        }
        TaskTrace.event("ERROR", "refresh.container", detail, 0, false);
    }

    private TaskModuleResult finish() {
        if (!missingChunks) {
            markerReads.forEach((binding, entries) ->
                    entries.keySet().retainAll(observedMarkers.get(binding)));
        }
        context.categoryMarkerCache().updateRefresh(markerReads, scope, markerPositions, visibility);
        String summary = "已刷新 " + succeeded + " 个容器";
        terminal = failures == 0 ? TaskModuleResult.succeeded(summary)
                : TaskModuleResult.failed(summary + "，" + failures + " 处失败。"
                        + "首个失败位置 " + firstFailure);
        return terminal;
    }

    @Override
    public void requestStop() {
        stopRequested = true;
    }

    @Override
    public boolean safeToStop() {
        return !stopRequested || terminal != null;
    }

    @Override
    public void cancel() {
        stopTravel();
        if (inspecting) {
            context.containerInspections().cancelInspection();
            inspecting = false;
        }
        terminal = TaskModuleResult.succeeded("已停止刷新");
    }

    interface RefreshWorld {
        boolean available();

        BlockPosition position();

        String scope();

        boolean chunkLoaded(BlockPosition center);

        ContainerScanResult scan(RegionDefinition chunk, ContainerCacheRefresh options);

        Object memoryStamp(ContainerTarget target);
    }

    private record ClientWorld(Minecraft minecraft, MinecraftContainerRegionScanner scanner,
            ClientLevel level) implements RefreshWorld {
        @Override
        public boolean available() {
            return level != null && minecraft.level == level && minecraft.player != null
                    && minecraft.player.isAlive() && minecraft.gameMode != null;
        }

        @Override
        public BlockPosition position() {
            var point = minecraft.player.blockPosition();
            return new BlockPosition(point.getX(), point.getY(), point.getZ());
        }

        @Override
        public String scope() {
            return MinecraftWorldScope.identify(minecraft);
        }

        @Override
        public boolean chunkLoaded(BlockPosition center) {
            return level.hasChunk(center.x() >> 4, center.z() >> 4);
        }

        @Override
        public ContainerScanResult scan(RegionDefinition chunk, ContainerCacheRefresh options) {
            return scanner.scanRefresh(chunk, options);
        }

        @Override
        public MemoryStamp memoryStamp(ContainerTarget target) {
            var bank = MemoryBankAccess.INSTANCE.getLoaded().orElse(null);
            if (bank == null) {
                return null;
            }
            for (BlockPosition point : target.entrances()) {
                var position = new BlockPos(point.x(), point.y(), point.z());
                var memory = bank.getMemory(level, position).orElse(null);
                if (memory != null) {
                    return new MemoryStamp(memory, memory.realTimestamp());
                }
            }
            return null;
        }
    }

    private record MemoryStamp(Memory record, Instant timestamp) {
    }
}
