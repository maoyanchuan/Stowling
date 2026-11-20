package dev.stowling.v2.control;

public record AutoClassifyAreaRequest(
        String categoryZoneRef,
        dev.stowling.v2.model.StoragePolicy storagePolicy
) {
    public AutoClassifyAreaRequest(String categoryZoneRef) {
        this(categoryZoneRef, null);
    }
}
