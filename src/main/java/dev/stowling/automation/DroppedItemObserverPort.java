package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.List;

public interface DroppedItemObserverPort {
    List<DroppedItem> observe(BlockPosition center, int radius);
}
