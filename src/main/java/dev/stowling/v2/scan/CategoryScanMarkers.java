package dev.stowling.v2.scan;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryMarker;
import java.util.List;
import java.util.Map;

/** Physical markers keep their coordinates; attachments only identify their logical display slot. */
public record CategoryScanMarkers(List<CategoryMarker> markers,
        Map<BlockPosition, BlockPosition> anchors) {
    public CategoryScanMarkers {
        markers = List.copyOf(markers);
        anchors = Map.copyOf(anchors);
    }

    public BlockPosition anchor(CategoryMarker marker) {
        return anchors.getOrDefault(marker.position(), marker.position());
    }
}
