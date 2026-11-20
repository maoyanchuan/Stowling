package dev.stowling.v2.model;

public record AutoClassifyAreaDefinition(
        String categoryZoneRef,
        dev.stowling.v2.model.StoragePolicy storagePolicy
) {
    public AutoClassifyAreaDefinition(String categoryZoneRef) {
        this(categoryZoneRef, null);
    }

    public AutoClassifyAreaDefinition {
        categoryZoneRef = requireZoneReference(categoryZoneRef);
    }

    private static String requireZoneReference(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "categoryZoneRef is required"
            );
        }
        ResourceReference reference = ResourceReference.parse(value);
        if (reference.type() != ResourceType.CATEGORY_ZONE) {
            throw new IllegalArgumentException(
                    "categoryZoneRef must reference CATEGORY_ZONE"
            );
        }
        return reference.encode();
    }
}
