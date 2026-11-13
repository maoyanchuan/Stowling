package dev.stowling.domain;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record AuditSchedule(
        String id,
        AuditMode mode,
        Duration interval,
        List<String> containerIds
) {
    public AuditSchedule {
        id = DomainChecks.requireText(id, "audit schedule id");
        mode = Objects.requireNonNull(mode, "mode");
        interval = Objects.requireNonNull(interval, "interval");
        Objects.requireNonNull(containerIds, "containerIds");
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        for (String containerId : containerIds) {
            uniqueIds.add(DomainChecks.requireText(containerId, "container id"));
        }
        containerIds = List.copyOf(uniqueIds);
        if (interval.isNegative()) {
            throw new IllegalArgumentException("interval must not be negative");
        }
        if (mode == AuditMode.MANUAL && !interval.isZero()) {
            throw new IllegalArgumentException("manual audit interval must be zero");
        }
        if (mode == AuditMode.SCHEDULED && interval.isZero()) {
            throw new IllegalArgumentException("scheduled audit interval must be positive");
        }
    }
}
