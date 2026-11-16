package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.Objects;
import java.util.UUID;

final class ResourceReferenceSupport {
    private ResourceReferenceSupport() {
    }

    static ResourceReference reference(
            TaskStep step,
            String key,
            ResourceType fallbackType,
            String... aliases
    ) {
        String value = parameter(step, key, aliases);
        return parse(value, fallbackType);
    }

    static ResourceReference optionalReference(
            TaskStep step,
            String key,
            ResourceType fallbackType,
            String... aliases
    ) {
        String value = optionalParameter(step, key, aliases);
        return value == null ? null : parse(value, fallbackType);
    }

    static String parameter(
            TaskStep step,
            String key,
            String... aliases
    ) {
        String value = optionalParameter(step, key, aliases);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing task parameter: " + key);
        }
        return value.trim();
    }

    static String optionalParameter(
            TaskStep step,
            String key,
            String... aliases
    ) {
        String value = step.parameters().get(key);
        for (String alias : aliases) {
            if (value != null && !value.isBlank()) {
                break;
            }
            value = step.parameters().get(alias);
        }
        return value == null || value.isBlank() ? null : value.trim();
    }

    static CoordinatePoint coordinate(
            TaskExecutionContext context,
            ResourceReference reference
    ) {
        if (reference.type() != ResourceType.COORDINATE) {
            throw new IllegalArgumentException(
                    "target must reference a coordinate"
            );
        }
        CoordinatePoint point = context.coordinates().get(reference.uuid());
        return Objects.requireNonNull(
                point,
                "coordinate does not exist: " + reference
        );
    }

    static RegionDefinition region(
            TaskExecutionContext context,
            ResourceReference reference
    ) {
        if (reference.type() != ResourceType.REGION) {
            throw new IllegalArgumentException("target must reference a region");
        }
        RegionDefinition region = context.regions().get(reference.uuid());
        return Objects.requireNonNull(
                region,
                "region does not exist: " + reference
        );
    }

    static CategoryZoneDefinition categoryZone(
            TaskExecutionContext context,
            ResourceReference reference
    ) {
        if (reference.type() != ResourceType.CATEGORY_ZONE) {
            throw new IllegalArgumentException(
                    "target must reference a category zone"
            );
        }
        CategoryZoneDefinition zone = context.categoryZones().get(
                reference.uuid()
        );
        return Objects.requireNonNull(
                zone,
                "category zone does not exist: " + reference
        );
    }

    static ContainerTarget output(
            TaskRunWorkspace workspace,
            ResourceReference reference
    ) {
        if (reference.type() != ResourceType.OUTPUT) {
            throw new IllegalArgumentException(
                    "target must reference a dynamic output"
            );
        }
        return switch (reference.key()) {
            case "container" -> workspace.selectedContainer().orElseThrow(
                    () -> new IllegalStateException("no container selected")
            );
            case "storage" -> workspace.selectedStorageTarget().orElseThrow(
                    () -> new IllegalStateException("no storage target selected")
            );
            case "marker" -> workspace.selectedMarkerContainer().orElseThrow(
                    () -> new IllegalStateException("no marker container selected")
            );
            default -> throw new IllegalArgumentException(
                    "unknown output reference: " + reference.key()
            );
        };
    }

    static UUID taskId(TaskStep step) {
        ResourceReference reference = reference(
                step,
                TaskModuleIds.TASK_REF,
                ResourceType.TASK
        );
        if (reference.type() != ResourceType.TASK) {
            throw new IllegalArgumentException("taskRef must reference a task");
        }
        return reference.uuid();
    }

    private static ResourceReference parse(
            String value,
            ResourceType fallbackType
    ) {
        String trimmed = value.trim();
        if (trimmed.indexOf(':') < 0) {
            return new ResourceReference(
                    fallbackType,
                    UUID.fromString(trimmed).toString()
            );
        }
        return ResourceReference.parse(trimmed);
    }
}
