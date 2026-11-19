package dev.stowling.automation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Counts pickup receipts independently of inventory slots being emptied at the same time. */
public final class InventoryPickupTracker {
    private Object session;
    private final Map<String, Long> counts = new HashMap<>();

    public synchronized void record(Object currentSession, String itemId, int amount) {
        selectSession(currentSession);
        if (amount <= 0) throw new IllegalArgumentException("pickup amount must be positive");
        counts.merge(Objects.requireNonNull(itemId, "itemId"), (long) amount, Math::addExact);
    }

    public synchronized long count(Object currentSession, String itemId) {
        selectSession(currentSession);
        return counts.getOrDefault(itemId, 0L);
    }

    private void selectSession(Object currentSession) {
        if (session != currentSession) {
            session = currentSession;
            counts.clear();
        }
    }
}
