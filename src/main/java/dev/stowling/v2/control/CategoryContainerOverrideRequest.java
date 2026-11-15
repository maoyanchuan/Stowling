package dev.stowling.v2.control;

import java.util.List;

public record CategoryContainerOverrideRequest(
        int storageX,
        int storageY,
        int storageZ,
        String mode,
        List<String> itemIds
) {
    public CategoryContainerOverrideRequest {
        itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
    }
}
