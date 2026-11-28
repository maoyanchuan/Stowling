package dev.stowling.v2.scan;

import dev.stowling.v2.category.CategoryMarker;
import dev.stowling.v2.model.AreaBounds;
import java.util.List;
import java.util.Map;
import dev.stowling.domain.BlockPosition;

/** Transient client-thread observation; never accepts world facts from the browser. */
public record CategoryScanSnapshot(AreaBounds area, String dimension,
        List<CategoryScanBlock> blocks, List<CategoryMarker> markers,
        Map<BlockPosition, BlockPosition> markerAnchors) {
    public CategoryScanSnapshot(AreaBounds area, String dimension,
            List<CategoryScanBlock> blocks, List<CategoryMarker> markers) {
        this(area, dimension, blocks, markers, Map.of());
    }

    public CategoryScanSnapshot {
        CategoryScanDefinition.validateArea(area);
        blocks = List.copyOf(blocks);
        markers = List.copyOf(markers);
        markerAnchors = Map.copyOf(markerAnchors);
    }

    public BlockPosition anchor(CategoryMarker marker) {
        return markerAnchors.getOrDefault(marker.position(), marker.position());
    }
}
