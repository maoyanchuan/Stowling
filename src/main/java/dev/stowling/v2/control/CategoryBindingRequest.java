package dev.stowling.v2.control;

import java.util.List;
import dev.stowling.v2.model.AreaBounds;

public record CategoryBindingRequest(
        int markerFirstX,
        int markerFirstY,
        int markerFirstZ,
        int markerSecondX,
        int markerSecondY,
        int markerSecondZ,
        int storageFirstX,
        int storageFirstY,
        int storageFirstZ,
        int storageSecondX,
        int storageSecondY,
        int storageSecondZ,
        String ruleId,
        List<String> ignoredMarkerBlockIds,
        List<CategoryContainerOverrideRequest> containerOverrides,
        AreaBounds standingArea,
        dev.stowling.v2.model.StoragePolicy storagePolicy
) {
    public CategoryBindingRequest {
        ignoredMarkerBlockIds = ignoredMarkerBlockIds == null
                ? List.of()
                : List.copyOf(ignoredMarkerBlockIds);
        containerOverrides = containerOverrides == null
                ? List.of()
                : List.copyOf(containerOverrides);
    }

    public CategoryBindingRequest(
            int markerFirstX, int markerFirstY, int markerFirstZ,
            int markerSecondX, int markerSecondY, int markerSecondZ,
            int storageFirstX, int storageFirstY, int storageFirstZ,
            int storageSecondX, int storageSecondY, int storageSecondZ,
            String ruleId, List<String> ignoredMarkerBlockIds,
            List<CategoryContainerOverrideRequest> containerOverrides, AreaBounds standingArea
    ) {
        this(markerFirstX, markerFirstY, markerFirstZ, markerSecondX, markerSecondY, markerSecondZ,
                storageFirstX, storageFirstY, storageFirstZ, storageSecondX, storageSecondY,
                storageSecondZ, ruleId, ignoredMarkerBlockIds, containerOverrides, standingArea,
                dev.stowling.v2.model.StoragePolicy.DEFAULT);
    }

    public CategoryBindingRequest(
            int markerFirstX, int markerFirstY, int markerFirstZ,
            int markerSecondX, int markerSecondY, int markerSecondZ,
            int storageFirstX, int storageFirstY, int storageFirstZ,
            int storageSecondX, int storageSecondY, int storageSecondZ,
            String ruleId, List<String> ignoredMarkerBlockIds,
            List<CategoryContainerOverrideRequest> containerOverrides
    ) {
        this(markerFirstX, markerFirstY, markerFirstZ, markerSecondX, markerSecondY, markerSecondZ,
                storageFirstX, storageFirstY, storageFirstZ, storageSecondX, storageSecondY,
                storageSecondZ, ruleId, ignoredMarkerBlockIds, containerOverrides, null);
    }
}
