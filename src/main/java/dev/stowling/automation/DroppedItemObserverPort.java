package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface DroppedItemObserverPort {
    List<DroppedItem> observe(BlockPosition center, int radius);

    default Optional<Map<String, Integer>> playerItemCounts() {
        return Optional.empty();
    }
}
