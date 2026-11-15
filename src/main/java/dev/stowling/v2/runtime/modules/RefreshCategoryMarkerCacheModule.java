package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class RefreshCategoryMarkerCacheModule
        implements TaskModuleExecutor {
    private static final int CONTAINER_NAVIGATION_RADIUS = 4;
    private static final Set<String> MARKER_CONTAINER_TYPES = Set.of(
            "minecraft:chest",
            "minecraft:trapped_chest",
            "minecraft:barrel"
    );

    private final TaskExecutionContext context;
    private final CategoryZoneDefinition zone;
    private final int maxVolume;
    private final Map<UUID, Map<BlockPosition, Set<String>>> cache =
            new LinkedHashMap<>();
    private final List<MarkerTarget> targets = new ArrayList<>();
    private int targetIndex;
    private MarkerTarget activeTarget;
    private boolean navigationStarted;
    private boolean inspectionActive;

    RefreshCategoryMarkerCacheModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        UUID zoneId = UUID.fromString(
                step.parameters().get(ConfigValidator.CATEGORY_ZONE_ID)
        );
        this.zone = context.categoryZones().get(zoneId);
        if (zone == null) {
            throw new IllegalArgumentException(
                    "category zone does not exist: " + zoneId
            );
        }
        this.maxVolume = TaskModuleSupport.integerParameter(
                step,
                ConfigValidator.MAX_VOLUME,
                TaskModuleSupport.DEFAULT_SCAN_VOLUME
        );
    }

    @Override
    public TaskModuleResult start() {
        Map<BlockPosition, MarkerTarget> byPosition =
                new LinkedHashMap<>();
        for (CategoryBindingDefinition binding : zone.bindings()) {
            if (!ContainerContentsCategoryRule.ID.equals(binding.ruleId())) {
                continue;
            }
            cache.put(binding.id(), new LinkedHashMap<>());
            ContainerScanResult scan = context.containerRegions().scan(
                    markerRegion(binding),
                    MARKER_CONTAINER_TYPES,
                    maxVolume
            );
            if (!scan.succeeded()) {
                return TaskModuleResult.failed(scan.message());
            }
            if (scan.containers().isEmpty()) {
                return TaskModuleResult.failed(
                        "分类物识别区没有箱子或木桶: " + zone.name()
                );
            }
            for (ContainerTarget target : scan.containers()) {
                MarkerTarget markerTarget = byPosition.computeIfAbsent(
                        target.position(),
                        ignored -> new MarkerTarget(target)
                );
                markerTarget.bindingIds().add(binding.id());
            }
        }
        if (cache.isEmpty()) {
            return TaskModuleResult.failed(
                    "分类区没有使用“分类标记容器内容”规则"
            );
        }
        targets.addAll(byPosition.values());
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (targetIndex >= targets.size()) {
            context.categoryMarkerCache().replaceBindings(cache);
            return TaskModuleResult.succeeded(
                    "分类容器缓存已更新，共读取 "
                            + targets.size() + " 个容器"
            );
        }
        if (activeTarget == null) {
            activeTarget = targets.get(targetIndex);
        }
        NavigationStatus navigation = navigate();
        if (navigation == NavigationStatus.FAILED) {
            return TaskModuleResult.failed(
                    "无法前往分类标记容器 "
                            + activeTarget.target().position()
            );
        }
        if (navigation != NavigationStatus.ARRIVED) {
            return TaskModuleResult.waiting("正在前往分类标记容器");
        }
        ContainerInspectionPort inspections =
                context.containerInspections();
        ContainerInspectionResult result;
        if (!inspectionActive) {
            inspectionActive = true;
            result = inspections.startInspection(activeTarget.target());
        } else {
            result = inspections.pollInspection();
        }
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        inspectionActive = false;
        if (result.state() == ContainerTransferState.FAILED) {
            return TaskModuleResult.failed(result.message());
        }
        storeTargetContents(activeTarget, result.itemIds());
        activeTarget = null;
        navigationStarted = false;
        targetIndex++;
        return targetIndex >= targets.size()
                ? tick()
                : TaskModuleResult.running("分类容器缓存读取中");
    }

    @Override
    public void cancel() {
        context.containerInspections().cancelInspection();
        context.navigation().cancel();
    }

    private NavigationStatus navigate() {
        if (!navigationStarted) {
            navigationStarted = true;
            return context.navigation().startNear(
                    activeTarget.target().position(),
                    CONTAINER_NAVIGATION_RADIUS
            );
        }
        return context.navigation().status(
                activeTarget.target().position(),
                CONTAINER_NAVIGATION_RADIUS
        );
    }

    private void storeTargetContents(
            MarkerTarget markerTarget,
            Set<String> itemIds
    ) {
        for (UUID bindingId : markerTarget.bindingIds()) {
            cache.get(bindingId).put(
                    markerTarget.target().position(),
                    Set.copyOf(itemIds)
            );
        }
    }

    private static RegionDefinition markerRegion(
            CategoryBindingDefinition binding
    ) {
        AreaBounds area = binding.markerArea();
        return new RegionDefinition(
                binding.id(),
                "category-marker",
                area.firstX(),
                area.firstY(),
                area.firstZ(),
                area.secondX(),
                area.secondY(),
                area.secondZ()
        );
    }

    private static final class MarkerTarget {
        private final ContainerTarget target;
        private final Set<UUID> bindingIds = new LinkedHashSet<>();

        private MarkerTarget(ContainerTarget target) {
            this.target = target;
        }

        private ContainerTarget target() {
            return target;
        }

        private Set<UUID> bindingIds() {
            return bindingIds;
        }
    }
}
