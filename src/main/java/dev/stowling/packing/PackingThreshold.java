package dev.stowling.packing;

import dev.stowling.domain.PackingStation;
import java.util.Objects;

public record PackingThreshold(PackingThresholdMode mode, int value) {
    public PackingThreshold {
        Objects.requireNonNull(mode, "mode");
        int maximum = switch (mode) {
            case OCCUPIED_SLOT_RATIO -> 100;
            case ITEM_COUNT -> 1728;
        };
        if (value < 1 || value > maximum) {
            throw new IllegalArgumentException(
                    "threshold for " + mode + " must be between 1 and " + maximum
            );
        }
    }

    public static PackingThreshold from(PackingStation station) {
        Objects.requireNonNull(station, "station");
        PackingThresholdMode mode = switch (station.completionTrigger()) {
            case ON_OCCUPANCY -> PackingThresholdMode.OCCUPIED_SLOT_RATIO;
            case ON_ITEM_COUNT -> PackingThresholdMode.ITEM_COUNT;
        };
        return new PackingThreshold(mode, station.completionThreshold());
    }

    public boolean reached(PackingBoxContents contents) {
        Objects.requireNonNull(contents, "contents");
        return switch (mode) {
            case OCCUPIED_SLOT_RATIO -> contents.occupiedRatioAtLeast(value);
            case ITEM_COUNT -> contents.totalCount() >= value;
        };
    }
}
