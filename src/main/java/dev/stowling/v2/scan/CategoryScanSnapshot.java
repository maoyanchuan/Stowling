package dev.stowling.v2.scan;

import dev.stowling.v2.category.CategoryMarker;
import dev.stowling.v2.model.AreaBounds;
import java.util.List;

/** Transient client-thread observation; never accepts world facts from the browser. */
public record CategoryScanSnapshot(AreaBounds area, String dimension,
        List<CategoryScanBlock> blocks, List<CategoryMarker> markers) {
    public CategoryScanSnapshot {
        CategoryScanDefinition.validateArea(area);
        blocks = List.copyOf(blocks);
        markers = List.copyOf(markers);
    }
}
