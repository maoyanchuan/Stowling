package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DroppedItemObserverPort {
    List<DroppedItem> observe(BlockPosition center, int radius);

    List<DroppedItem> observe(RegionDefinition region);

    default Optional<Map<String, Integer>> playerItemCounts() {
        return Optional.empty();
    }
}
