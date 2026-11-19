package dev.stowling.automation;

import java.util.List;
import java.util.Objects;

/** One category's destinations at its original position in the recognized layout. */
public record ClassificationLayoutEntry(String itemId, List<ContainerTarget> containers) {
    public ClassificationLayoutEntry {
        itemId = Objects.requireNonNull(itemId, "itemId");
        containers = List.copyOf(containers);
    }
}
