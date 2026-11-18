package dev.stowling.automation;

import dev.stowling.v2.model.StoragePackingPlanner;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Balances mixed batches while leaving full-box-only categories enough room to pack. */
public final class ClassificationBatchPolicy {
    private final Set<String> fullBoxCategories;
    private final Map<String, Integer> stacksByCategory = new LinkedHashMap<>();
    private String dedicatedCategory;
    private int confirmedStacks;

    public ClassificationBatchPolicy(Set<String> fullBoxCategories) {
        this.fullBoxCategories = Set.copyOf(fullBoxCategories);
    }

    public boolean hasCapacity() {
        return confirmedStacks < StoragePackingPlanner.BOX_SLOTS;
    }

    public boolean accepts(String key) {
        String category = ItemIdentity.category(key);
        if (dedicatedCategory != null) {
            return dedicatedCategory.equals(category);
        }
        return confirmedStacks == 0 || ItemIdentity.isBox(key)
                || !fullBoxCategories.contains(category);
    }

    public int priority(String key) {
        return stacksByCategory.getOrDefault(ItemIdentity.category(key), 0);
    }

    public void recordConfirmed(String key) {
        String category = ItemIdentity.category(key);
        if (confirmedStacks == 0 && !ItemIdentity.isBox(key)
                && fullBoxCategories.contains(category)) {
            dedicatedCategory = category;
        }
        stacksByCategory.merge(category, 1, Math::addExact);
        confirmedStacks++;
    }
}
