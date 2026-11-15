package dev.stowling.automation.module;

import dev.stowling.automation.AutomationStep;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.NamedCoordinate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class TaskContainerParameters {
    private TaskContainerParameters() {
    }

    public static Optional<ContainerRef> explicitContainer(
            AutomationStep step,
            Supplier<StowlingConfig> configSupplier,
            String coordinateKey,
            String typeKey
    ) {
        Optional<String> coordinateId = ModuleParameters.optional(step, coordinateKey);
        if (coordinateId.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(container(
                configSupplier,
                coordinateId.orElseThrow(),
                containerType(step, typeKey)
        ));
    }

    public static ContainerRef explicitContainerRequired(
            AutomationStep step,
            Supplier<StowlingConfig> configSupplier,
            String coordinateKey,
            String typeKey
    ) {
        String coordinateId = ModuleParameters.required(step, coordinateKey);
        return container(
                configSupplier,
                coordinateId,
                containerType(step, typeKey)
        );
    }

    public static NamedCoordinate coordinate(
            Supplier<StowlingConfig> configSupplier,
            String coordinateId
    ) {
        StowlingConfig config = Objects.requireNonNull(
                Objects.requireNonNull(configSupplier, "configSupplier").get(),
                "config"
        );
        String checkedId = ModuleParameters.requireText(coordinateId, "coordinateId");
        return config.coordinatePoints().stream()
                .filter(candidate -> candidate.id().equals(checkedId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "named coordinate is unavailable: " + checkedId
                ));
    }

    public static ContainerType containerType(AutomationStep step, String key) {
        return ModuleParameters.optional(step, key)
                .map(value -> parseContainerType(key, value))
                .orElse(ContainerType.CHEST);
    }

    public static ContainerType parseContainerType(String key, String value) {
        Objects.requireNonNull(value, "container type");
        return parseContainerTypeValue(key, value);
    }

    public static ContainerRef container(
            Supplier<StowlingConfig> configSupplier,
            String coordinateId,
            ContainerType type
    ) {
        NamedCoordinate coordinate = coordinate(configSupplier, coordinateId);
        return new ContainerRef(
                coordinate.id(),
                coordinate.dimension(),
                coordinate.anchorPosition(),
                Objects.requireNonNull(type, "type")
        );
    }

    public static void requireSameDimension(
            List<ContainerRef> containers,
            String fieldName
    ) {
        if (containers.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be empty");
        }
        String dimension = containers.getFirst().dimension();
        boolean sameDimension = containers.stream()
                .allMatch(container -> container.dimension().equals(dimension));
        if (!sameDimension) {
            throw new IllegalArgumentException(
                    fieldName + " containers must share one dimension"
            );
        }
    }

    private static ContainerType parseContainerTypeValue(String key, String value) {
        try {
            return ContainerType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    key + " must be a supported container type"
            );
        }
    }
}
