package dev.stowling.control;

import java.util.Objects;

public record PickupRequest(String siteId) {
    public PickupRequest {
        Objects.requireNonNull(siteId, "siteId");
        siteId = siteId.trim();
        if (siteId.isEmpty()) {
            throw new IllegalArgumentException("siteId must not be blank");
        }
    }
}
