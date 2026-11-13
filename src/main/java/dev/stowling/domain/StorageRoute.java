package dev.stowling.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record StorageRoute(
        String id,
        List<String> itemIds,
        List<String> itemTags,
        int priority,
        ContainerRef bulkContainer,
        PackingTrigger packingThresholdMode,
        Integer packingThreshold,
        List<String> packingStationIds
) {
    public StorageRoute {
        id = DomainChecks.requireText(id, "storage route id");
        itemIds = uniqueTexts(itemIds, "item id");
        itemTags = uniqueTexts(itemTags, "item tag");
        bulkContainer = Objects.requireNonNull(bulkContainer, "bulkContainer");
        packingThresholdMode = Objects.requireNonNull(
                packingThresholdMode,
                "packingThresholdMode"
        );
        packingStationIds = packingStationIds == null
                ? List.of()
                : uniqueTexts(packingStationIds, "packing station id");
        if (itemIds.isEmpty() && itemTags.isEmpty()) {
            throw new IllegalArgumentException("storage route must select items or tags");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
        if (packingThreshold != null && packingThreshold < 1) {
            throw new IllegalArgumentException("packingThreshold must be positive");
        }
        if ((packingThreshold == null) != packingStationIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "packingThreshold and packingStationIds must be set together"
            );
        }
        if (packingThresholdMode == PackingTrigger.ON_OCCUPANCY
                && packingThreshold != null
                && packingThreshold > 100) {
            throw new IllegalArgumentException(
                    "occupancy packingThreshold must be between 1 and 100"
            );
        }
    }

    public StorageRoute(
            String id,
            List<String> itemIds,
            List<String> itemTags,
            int priority,
            ContainerRef bulkContainer,
            Integer packingThreshold,
            String packingStationId
    ) {
        this(
                id,
                itemIds,
                itemTags,
                priority,
                bulkContainer,
                PackingTrigger.ON_ITEM_COUNT,
                packingThreshold,
                packingStationId == null ? List.of() : List.of(packingStationId)
        );
    }

    public boolean packingEnabled() {
        return !packingStationIds.isEmpty();
    }

    public String packingStationId() {
        return packingStationIds.isEmpty() ? null : packingStationIds.getFirst();
    }

    private static List<String> uniqueTexts(List<String> values, String fieldName) {
        Objects.requireNonNull(values, fieldName + " list");
        LinkedHashSet<String> uniqueValues = new LinkedHashSet<>();
        for (String value : values) {
            uniqueValues.add(DomainChecks.requireText(value, fieldName));
        }
        return List.copyOf(uniqueValues);
    }
}
