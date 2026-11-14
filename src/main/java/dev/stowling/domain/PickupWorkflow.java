package dev.stowling.domain;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

public record PickupWorkflow(
        String id,
        String name,
        String dimension,
        List<PickupRegionBinding> regions,
        List<String> cacheIds,
        CachePolicy cachePolicy,
        ClassificationTiming classificationTiming,
        CompletionMode completionMode,
        Duration completionIdleDelay
) {
    public PickupWorkflow {
        id = DomainChecks.requireText(id, "pickup workflow id");
        name = DomainChecks.requireText(name, "pickup workflow name");
        dimension = DomainChecks.requireText(dimension, "dimension");
        regions = List.copyOf(Objects.requireNonNull(regions, "regions"));
        cacheIds = List.copyOf(Objects.requireNonNull(cacheIds, "cacheIds"));
        cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        classificationTiming = Objects.requireNonNull(
                classificationTiming,
                "classificationTiming"
        );
        completionMode = Objects.requireNonNull(completionMode, "completionMode");
        completionIdleDelay = Objects.requireNonNull(
                completionIdleDelay,
                "completionIdleDelay"
        );
        if (completionIdleDelay.isNegative()) {
            throw new IllegalArgumentException("completionIdleDelay must not be negative");
        }
    }
}
