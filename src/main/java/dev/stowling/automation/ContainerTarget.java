package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.StoragePolicy;
import java.util.List;
import java.util.Objects;

public record ContainerTarget(
        BlockPosition position,
        String type,
        AreaBounds standingArea,
        StoragePolicy storagePolicy,
        BlockPosition partner,
        boolean standingRangeEnabled
) {
    public ContainerTarget(BlockPosition position, String type, AreaBounds standingArea,
            StoragePolicy storagePolicy, BlockPosition partner) {
        this(position, type, standingArea, storagePolicy, partner, false);
    }

    public ContainerTarget {
        storagePolicy = storagePolicy == null
                ? StoragePolicy.DEFAULT : storagePolicy;
        standingRangeEnabled = standingRangeEnabled && standingArea != null;
        position = Objects.requireNonNull(position, "position");
        type = requireText(type, "type");
    }

    public ContainerTarget(BlockPosition position, String type, AreaBounds standingArea,
            StoragePolicy storagePolicy) {
        this(position, type, standingArea, storagePolicy, null);
    }

    public ContainerTarget(BlockPosition position, String type, AreaBounds standingArea) {
        this(position, type, standingArea, null);
    }

    public ContainerTarget(BlockPosition position, String type) {
        this(position, type, null);
    }

    public List<BlockPosition> entrances() {
        return partner == null ? List.of(position) : List.of(position, partner);
    }

    public ContainerTarget withStandingArea(AreaBounds area, StoragePolicy policy) {
        return new ContainerTarget(position, type, area, policy, partner, standingRangeEnabled);
    }

    public ContainerTarget withStandingRange(boolean enabled) {
        return new ContainerTarget(position, type, standingArea, storagePolicy, partner, enabled);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return trimmed;
    }
}
