package dev.stowling.automation;

import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePackingPlanner;
import dev.stowling.v2.model.StoragePolicy;
import java.util.List;

/** Tests current slot capacity for a classified item using the destination's storage policy. */
public final class ClassificationOverflowCapacity {
    private ClassificationOverflowCapacity() {
    }

    public static boolean hasSpace(StoragePolicy policy, List<ShulkerTransferPlanner.Slot> stock,
            int slots, String key, int stackSize, boolean partialBoxHasSpace) {
        if (slots < 1 || stock.size() < slots || partialBoxHasSpace) return true;
        if (policy.mode() == StorageMode.NORMAL) {
            return stock.stream().anyMatch(slot ->
                    slot.key().equals(key) && slot.count() < slot.stackSize());
        }
        if (policy.mode() == StorageMode.BOX_ONLY) return false;
        int matchingSlack = 0;
        int sameCategory = 0;
        int otherLooseSlots = 0;
        for (var slot : stock) {
            if (ItemIdentity.isBox(slot.key())) continue;
            if (ItemIdentity.base(slot.key()).equals(ItemIdentity.base(key))) {
                sameCategory += slot.count();
                if (slot.key().equals(key)) matchingSlack += slot.stackSize() - slot.count();
            } else {
                otherLooseSlots++;
            }
        }
        return StoragePackingPlanner.looseSpace(policy, slots, stackSize,
                sameCategory, otherLooseSlots, stock.size(), matchingSlack) > 0;
    }
}
