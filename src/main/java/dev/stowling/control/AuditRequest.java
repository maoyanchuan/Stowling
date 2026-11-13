package dev.stowling.control;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record AuditRequest(List<String> containerIds) {
    public AuditRequest {
        Objects.requireNonNull(containerIds, "containerIds");
        Set<String> uniqueIds = new LinkedHashSet<>();
        for (String containerId : containerIds) {
            Objects.requireNonNull(containerId, "containerId");
            String trimmed = containerId.trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("containerId must not be blank");
            }
            if (!uniqueIds.add(trimmed)) {
                throw new IllegalArgumentException("containerIds must not contain duplicates");
            }
        }
        containerIds = List.copyOf(uniqueIds);
    }
}
