package dev.stowling.inventory;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.time.Instant;
import java.util.List;

/** Immutable view passed from the client thread to the authenticated browser API. */
public record WarehouseInventory(
        String state,
        String message,
        String currentDimension,
        Instant readAt,
        List<Container> containers,
        List<Area> areas
) {
    public WarehouseInventory {
        containers = List.copyOf(containers);
        areas = List.copyOf(areas);
    }

    public record Entry(String key, String itemId, String name, long loose, long boxed,
            int stackSize, String icon) {
        public Entry(String key, String itemId, String name, long loose, long boxed, int stackSize) {
            this(key, itemId, name, loose, boxed, stackSize, itemId);
        }
    }

    public record Box(String category, int occupiedSlots, List<Entry> items) {
        public Box {
            items = List.copyOf(items);
        }
    }

    public record Container(String id, String name, String dimension, List<BlockPosition> positions,
            Instant recordedAt, int slots, int occupiedSlots, int fullBoxes, int partialBoxes,
            int emptyBoxes, List<Entry> items, List<Box> boxes) {
        public Container {
            positions = List.copyOf(positions);
            items = List.copyOf(items);
            boxes = List.copyOf(boxes);
        }

        public Container(String id, String name, String dimension, List<BlockPosition> positions,
                Instant recordedAt, int slots, int occupiedSlots, int fullBoxes, int partialBoxes,
                int emptyBoxes, List<Entry> items) {
            this(id, name, dimension, positions, recordedAt, slots, occupiedSlots, fullBoxes,
                    partialBoxes, emptyBoxes, items, List.of());
        }
    }

    public record Area(String id, String name, String dimension, List<AreaBounds> bounds) {
        public Area {
            bounds = List.copyOf(bounds);
        }
    }
}
