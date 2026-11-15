package dev.stowling.v2.control;

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
        String ruleId
) {
}
