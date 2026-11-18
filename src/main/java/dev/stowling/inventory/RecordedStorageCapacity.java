package dev.stowling.inventory;

import dev.stowling.automation.ItemIdentity;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePackingPlanner;
import dev.stowling.v2.model.StoragePolicy;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class RecordedStorageCapacity {
    private RecordedStorageCapacity() {
    }

    public static boolean shouldReserveBox(WarehouseInventory.Container stock, StoragePolicy policy,
            Map<String, Integer> items, Map<String, Integer> stackSizes) {
        if (policy.mode() == StorageMode.NORMAL || stock != null && stock.slots() < 1) {
            return false;
        }
        Set<String> checked = new LinkedHashSet<>();
        for (String key : items.keySet()) {
            if (ItemIdentity.isBox(key) || items.get(key) <= 0) {
                continue;
            }
            String category = ItemIdentity.category(key);
            if (!checked.add(category)) {
                continue;
            }
            if (stock == null || stock.recordedAt() == null) {
                return false;
            }
            int stackSize = stackSizes.getOrDefault(key, stock.items().stream()
                    .filter(entry -> entry.key().equals(key))
                    .mapToInt(WarehouseInventory.Entry::stackSize).findFirst().orElse(0));
            if (stackSize < 1) {
                continue;
            }
            long incoming = items.entrySet().stream()
                    .filter(entry -> !ItemIdentity.isBox(entry.getKey())
                            && category.equals(ItemIdentity.category(entry.getKey())))
                    .mapToLong(Map.Entry::getValue).sum();
            long loose = 0;
            long otherSlots = 0;
            long slack = 0;
            for (WarehouseInventory.Entry entry : stock.items()) {
                if (ItemIdentity.isBox(entry.key())) {
                    continue;
                }
                if (category.equals(entry.itemId())) {
                    loose += entry.loose();
                    long remainder = entry.loose() % entry.stackSize();
                    if (remainder > 0 && items.containsKey(entry.key())) {
                        slack += entry.stackSize() - remainder;
                    }
                } else {
                    otherSlots += Math.ceilDiv(entry.loose(), entry.stackSize());
                }
            }
            long limit = Math.max(0, StoragePackingPlanner.looseLimit(policy, stock.slots(),
                    stackSize) - otherSlots * stackSize);
            long space = StoragePackingPlanner.looseSpace(policy, stock.slots(), stackSize,
                    loose, otherSlots, stock.occupiedSlots(), slack);
            long boxSpace = existingBoxSpace(stock, category, items.keySet(), stackSize);
            // Existing boxes absorb stock before a new empty box is needed.
            if ((loose > limit || incoming > space)
                    && incoming + Math.max(0, loose - limit) > boxSpace) {
                return true;
            }
            boolean partial = stock.boxes().stream().anyMatch(box ->
                    category.equals(box.category())
                            && (box.occupiedSlots() < StoragePackingPlanner.BOX_SLOTS
                                    || box.items().stream().anyMatch(entry ->
                                            entry.loose() < entry.stackSize())));
            if (partial && incoming > boxSpace) {
                return true;
            }
        }
        return false;
    }

    public static long existingBoxSpace(WarehouseInventory.Container stock, String category,
            Set<String> incomingKeys, int stackSize) {
        long space = 0;
        for (WarehouseInventory.Box box : stock.boxes()) {
            if (!category.equals(box.category())) {
                continue;
            }
            space += (long) Math.max(0, StoragePackingPlanner.BOX_SLOTS - box.occupiedSlots())
                    * stackSize;
            for (WarehouseInventory.Entry entry : box.items()) {
                if (incomingKeys.contains(entry.key())) {
                    space += Math.max(0, entry.stackSize() - entry.loose());
                }
            }
        }
        return space;
    }

    public static int rank(WarehouseInventory.Container stock, StoragePolicy policy,
            Map<String, Integer> items, Map<String, Integer> stackSizes) {
        if (stock == null || stock.recordedAt() == null || stock.slots() < 1 || items.isEmpty()) {
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
