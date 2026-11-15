package dev.stowling.v2.control;

import java.util.List;

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
        List<String> ignoredMarkerBlockIds
) {
    public CategoryBindingRequest {
        ignoredMarkerBlockIds = ignoredMarkerBlockIds == null
                ? List.of()
                : List.copyOf(ignoredMarkerBlockIds);
    }
}
