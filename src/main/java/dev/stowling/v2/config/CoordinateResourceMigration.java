package dev.stowling.v2.config;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Idempotently promotes legacy inline positions to shared coordinate references. */
public final class CoordinateResourceMigration {
    private CoordinateResourceMigration() {
    }

    public static StorageAutomationSettings migrate(ConfigStore store,
            StorageAutomationSettings settings) {
        V2ConfigSnapshot snapshot = store.read();
        AppConfig config = snapshot.config();
        List<CoordinatePoint> points = new ArrayList<>(config.coordinates());
        List<UUID> temporary = new ArrayList<>(settings.temporaryCoordinateIds());
        List<UUID> empty = new ArrayList<>(settings.emptyBoxCoordinateIds());
        settings.temporaryContainers().forEach(position ->
                temporary.add(coordinate(points, position, "暂存箱")));
        settings.emptyBoxContainers().forEach(position ->
                empty.add(coordinate(points, position, "空盒供应箱")));
        UUID fallback = settings.fallbackCoordinateId();
        if (settings.fallbackPackingPosition() != null) {
            fallback = coordinate(points, settings.fallbackPackingPosition(), "后备打包位置");
        }
        List<TaskDefinition> tasks = config.tasks().stream().map(task -> new TaskDefinition(
                task.id(), task.name(), task.priority(), task.repeatWhenIdle(),
                steps(task.steps(), points), task.interruptionMode(), task.interruptionTaskRef(),
                task.defaultInterruptionFlow(), task.requeueAfterInterruption(), task.folderId(),
                task.cleanupPolicy()))
                .toList();
        List<AutoClassifyDefinition> automatic = config.autoClassifyTasks().stream().map(definition ->
                new AutoClassifyDefinition(definition.id(), definition.name(), definition.enabled(),
                        definition.collectionEnabled(), definition.classificationEnabled(),
                        definition.detectionCoordinateRef(), definition.pickupRadius(),
                        definition.cacheLocationRef(), definition.classificationAreas().stream()
                                .map(area -> new AutoClassifyAreaDefinition(area.categoryZoneRef(),
                                        area.storagePolicy() != null ? area.storagePolicy()
                                                : config.categoryZones().stream().filter(zone ->
                                                        area.categoryZoneRef().equals(
                                                                "category-zone:" + zone.id()))
                                                        .flatMap(zone -> zone.bindings().stream())
                                                        .map(CategoryBindingDefinition::storagePolicy)
                                                        .findFirst().orElse(StoragePolicy.DEFAULT)))
                        .toList(), definition.collectionPriority(),
                        definition.overflowEnabled(), definition.cleanupPolicy(),
                        definition.unstackableRegionRef(),
                        definition.overflowDestinationRef(), definition.cachePolicy())).toList();
        var migrated = new StorageAutomationSettings(settings.cleanupEnabled(), List.of(),
                List.of(), null, temporary.stream().distinct().toList(),
                empty.stream().distinct().toList(), fallback, settings.temporaryRegionIds(),
                settings.overflowCoordinateIds(), settings.overflowRegionIds(),
                settings.unstackableOverflowRegionId(), settings.robotWarehouseEnabled(),
                settings.robotWarehouseCoordinateIds(), settings.robotWarehouseRegionIds());
        if (!points.equals(config.coordinates()) || !tasks.equals(config.tasks())
                || !automatic.equals(config.autoClassifyTasks())) {
            store.save(snapshot.revision(), new AppConfig(config.schemaVersion(), points, tasks,
                    config.regions(), config.categoryZones(), config.triggers(), config.taskFolders(),
                    automatic));
        }
        return migrated;
    }

    private static UUID coordinate(List<CoordinatePoint> points, BlockPosition position, String name) {
        CoordinatePoint existing = points.stream().filter(point -> point.dimension() == null
                && point.x() == position.x() && point.y() == position.y()
                && point.z() == position.z()).findFirst().orElse(null);
        if (existing != null) {
            return existing.id();
        }
        String candidate = name;
        for (int suffix = 2; ; suffix++) {
            String checked = candidate;
            if (points.stream().noneMatch(point -> point.name().equals(checked))) {
                break;
            }
            candidate = name + " " + suffix;
        }
        UUID id = UUID.randomUUID();
        points.add(new CoordinatePoint(id, candidate, position.x(), position.y(), position.z(),
                null, null));
        return id;
    }

    private static List<TaskStep> steps(List<TaskStep> steps, List<CoordinatePoint> points) {
        return steps.stream().map(step -> {
            Map<String, String> parameters = new LinkedHashMap<>(step.parameters());
            if (step.moduleId().equals("MOVE_TO_COORDINATE")
                    && parameters.keySet().equals(Set.of("x", "y", "z"))) {
                UUID id = coordinate(points, new BlockPosition(Integer.parseInt(parameters.get("x")),
                        Integer.parseInt(parameters.get("y")),
                        Integer.parseInt(parameters.get("z"))), "移动目标");
                parameters = Map.of("targetRef", "coordinate:" + id);
            }
            return new TaskStep(step.id(), step.kind(), step.moduleId(), parameters,
                    steps(step.steps(), points), steps(step.otherwise(), points));
        }).toList();
    }
}
