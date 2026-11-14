package dev.stowling.control;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

public record PickupRequest(String workflowId, Optional<String> regionId) {
    private static final Pattern SAFE_ID = Pattern.compile(
            "[a-z0-9][a-z0-9._-]{0,63}"
    );

    public PickupRequest {
        workflowId = requireId(workflowId, "workflowId");
        regionId = Objects.requireNonNull(regionId, "regionId")
                .map(value -> requireId(value, "regionId"));
    }

    public PickupRequest(String workflowId) {
        this(workflowId, Optional.empty());
    }

    private static String requireId(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (!SAFE_ID.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(
                    fieldName + " must match [a-z0-9][a-z0-9._-]{0,63}"
            );
        }
        return trimmed;
    }
}
