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
        Integer packingThreshold,
        String packingStationId
) {
    public StorageRoute {
        id = DomainChecks.requireText(id, "storage route id");
        itemIds = uniqueTexts(itemIds, "item id");
        itemTags = uniqueTexts(itemTags, "item tag");
        bulkContainer = Objects.requireNonNull(bulkContainer, "bulkContainer");
        if (itemIds.isEmpty() && itemTags.isEmpty()) {
            throw new IllegalArgumentException("storage route must select items or tags");
        }
        if (priority < 0) {
            throw new IllegalArgumentException("priority must not be negative");
        }
        if (packingStationId != null) {
            packingStationId = DomainChecks.requireText(packingStationId, "packing station id");
        }
        if (packingThreshold != null && packingThreshold < 1) {
            throw new IllegalArgumentException("packingThreshold must be positive");
        }
        if ((packingThreshold == null) != (packingStationId == null)) {
            throw new IllegalArgumentException(
                    "packingThreshold and packingStationId must be set together"
            );
        }
    }

    public boolean packingEnabled() {
        return packingStationId != null;
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
