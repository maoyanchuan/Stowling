package dev.stowling.v2.model;

import java.util.Objects;

public record CategoryZoneExpansionDefinition(
        AreaBounds area,
        CategoryZoneExpansionAxis direction,
        int step
) {
    public CategoryZoneExpansionDefinition {
        area = Objects.requireNonNull(area, "area");
        direction = Objects.requireNonNull(direction, "direction");
        if (step < 1) {
            throw new IllegalArgumentException(
                    "category zone expansion step must be positive"
            );
        }
    }
}
