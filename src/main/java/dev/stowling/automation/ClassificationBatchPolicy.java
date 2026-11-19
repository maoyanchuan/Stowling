package dev.stowling.automation;

import dev.stowling.v2.model.StoragePackingPlanner;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Follows recognized layout order while leaving room for packing resources. */
public final class ClassificationBatchPolicy {
    private final Set<String> fullBoxCategories;
    private final List<String> itemOrder;
    private final Map<String, Integer> stacksByCategory = new LinkedHashMap<>();
    private String dedicatedCategory;
    private int confirmedStacks;

    public ClassificationBatchPolicy(Set<String> fullBoxCategories) {
        this(fullBoxCategories, List.of());
    }

    public ClassificationBatchPolicy(Set<String> fullBoxCategories, List<String> itemOrder) {
        this.fullBoxCategories = Set.copyOf(fullBoxCategories);
        this.itemOrder = List.copyOf(itemOrder);
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
        if (!itemOrder.isEmpty()) {
            for (int index = 0; index < itemOrder.size(); index++) {
                if (ItemIdentity.matches(key, Set.of(itemOrder.get(index)))) return index;
            }
            return Integer.MAX_VALUE;
        }
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
