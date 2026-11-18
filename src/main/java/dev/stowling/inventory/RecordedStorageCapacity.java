package dev.stowling.inventory;

import dev.stowling.automation.ItemIdentity;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePolicy;
import java.util.Map;

public final class RecordedStorageCapacity {
    private RecordedStorageCapacity() {
    }

    public static int rank(WarehouseInventory.Container stock, StoragePolicy policy,
            Map<String, Integer> items, Map<String, Integer> stackSizes) {
        if (stock == null || stock.recordedAt() == null || items.isEmpty()) {
            return 1;
        }
        int free = Math.max(0, stock.slots() - stock.occupiedSlots());
        if (free == 0 && policy.mode() != StorageMode.NORMAL) {
            return stock.partialBoxes() > 0 ? 1 : 2;
        }
        if (free > 0 && policy.mode() != StorageMode.NORMAL) {
            return 0;
        }
        long needed = 0;
        for (Map.Entry<String, Integer> incoming : items.entrySet()) {
            long count = incoming.getValue();
            var recorded = stock.items().stream()
                    .filter(entry -> entry.key().equals(incoming.getKey())).findFirst();
            int stackSize = recorded.map(WarehouseInventory.Entry::stackSize)
                    .orElseGet(() -> stackSizes.getOrDefault(incoming.getKey(),
                            ItemIdentity.isBox(incoming.getKey()) ? 1 : 0));
            if (stackSize < 1) {
                if (free == 0) {
                    return 2;
                }
                needed += count;
                continue;
            }
            long loose = recorded.map(WarehouseInventory.Entry::loose).orElse(0L);
            long slack = (stackSize - loose % stackSize) % stackSize;
            needed += Math.ceilDiv(Math.max(0, count - slack), stackSize);
        }
        return needed <= free ? 0 : free == 0 ? 2 : 1;
    }
}
