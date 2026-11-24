package dev.stowling.v2.runtime;

import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinateCatalog;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.StorageAutomationSettings;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskTriggerDefinition;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Configuration actually prepared for this execution, retained independently of later edits. */
public record TaskDiagnosticContext(
        Instant capturedAt,
        Map<String, String> versions,
        TaskRunSnapshot run,
        TaskDefinition task,
        TaskDefinition interruptionTask,
        AutoClassifyDefinition taskSettings,
        StorageAutomationSettings storageSettings,
        Map<String, Integer> protectedItems,
        List<TaskTriggerDefinition> triggers,
        List<CoordinatePoint> coordinates,
        List<RegionDefinition> regions,
        List<CategoryZoneDefinition> categoryZones,
        List<CoordinateCatalog.Warehouse> warehouses
) {
    public TaskDiagnosticContext {
        versions = Map.copyOf(versions);
        protectedItems = Map.copyOf(protectedItems);
        triggers = List.copyOf(triggers);
        coordinates = List.copyOf(coordinates);
        regions = List.copyOf(regions);
        categoryZones = List.copyOf(categoryZones);
        warehouses = List.copyOf(warehouses);
    }
}
