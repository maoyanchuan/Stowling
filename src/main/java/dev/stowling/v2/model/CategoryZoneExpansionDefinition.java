package dev.stowling.v2.model;

import java.util.Objects;

public record CategoryZoneExpansionDefinition(
        AreaBounds area,
        CategoryZoneExpansionAxis direction,
        int step,
        int templateCount
) {
    public CategoryZoneExpansionDefinition {
        area = Objects.requireNonNull(area, "area");
        direction = Objects.requireNonNull(direction, "direction");
        if (step < 1) {
            throw new IllegalArgumentException(
                    "category zone expansion step must be positive"
            );
        }
        if (templateCount < 1 || templateCount > CategoryZoneTiler.MAX_SLICES) {
            throw new IllegalArgumentException(
                    "单片母版分类物数量必须在 1 到 "
                            + CategoryZoneTiler.MAX_SLICES + " 之间"
            );
        }
    }
}
