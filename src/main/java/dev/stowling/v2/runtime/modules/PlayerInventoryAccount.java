package dev.stowling.v2.runtime.modules;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class PlayerInventoryAccount {
    private final UUID ownerId;
    private final Map<String, Integer> collected = new LinkedHashMap<>();
    private final Map<String, Integer> carried = new LinkedHashMap<>();
    private final Map<String, Integer> managed = new LinkedHashMap<>();
    private final Set<String> observedPickupItems = new java.util.LinkedHashSet<>();

    public PlayerInventoryAccount(UUID ownerId) {
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
    }

    public UUID ownerId() {
        return ownerId;
    }

    public synchronized Map<String, Integer> managedItems() {
        return Map.copyOf(managed);
    }

    public synchronized void observePickupItem(String itemId) {
        observedPickupItems.add(Objects.requireNonNull(itemId, "itemId"));
    }

    public synchronized Set<String> observedPickupItems() {
        return Set.copyOf(observedPickupItems);
    }

    public synchronized Map<String, Integer> itemsForSource(String source) {
        return switch (source) {
            case "collected" -> Map.copyOf(collected);
            case "carried" -> Map.copyOf(carried);
            case "managed" -> Map.copyOf(managed);
            default -> Map.of();
        };
    }

    public synchronized void recordCollected(String itemId, int count) {
        add(collected, itemId, count);
        add(managed, itemId, count);
    }

    public synchronized void recordWithdrawn(String itemId, int count) {
        add(carried, itemId, count);
        add(managed, itemId, count);
    }

    public synchronized void recordManagedAcquired(String itemId, int count) {
        add(managed, itemId, count);
    }

    public synchronized void consumeManaged(String itemId, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        remove(managed, itemId, count);
    }

    public synchronized void removeCollected(String itemId, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        remove(collected, itemId, count);
    }

    public synchronized void confirmDeposit(
            String source,
            Map<String, Integer> requested,
            Map<String, Integer> remaining
    ) {
        if (!Set.of("collected", "carried", "managed", "inventory").contains(source)) {
            throw new IllegalArgumentException("unsupported item source: " + source);
        }
        Map<String, Integer> deposited = new LinkedHashMap<>(requested);
        remaining.forEach((itemId, count) -> remove(deposited, itemId, count));
        for (Map.Entry<String, Integer> entry : deposited.entrySet()) {
            String itemId = entry.getKey();
            int count = entry.getValue();
            switch (source) {
                case "collected" -> {
                    remove(collected, itemId, count);
                    remove(managed, itemId, count);
                }
                case "carried" -> {
                    remove(carried, itemId, count);
                    remove(managed, itemId, count);
                }
                case "managed" -> {
                    remove(managed, itemId, count);
                    int fromCarried = Math.min(count, carried.getOrDefault(itemId, 0));
                    remove(carried, itemId, fromCarried);
                    remove(collected, itemId, count - fromCarried);
                }
                case "inventory" -> {
                }
                default -> throw new IllegalArgumentException("unsupported item source: " + source);
            }
        }
    }

    private static void add(Map<String, Integer> ledger, String itemId, int count) {
        if (itemId == null || itemId.isBlank() || count <= 0) {
            throw new IllegalArgumentException("item id and count must be positive");
        }
        ledger.merge(itemId.trim(), count, Math::addExact);
    }

    private static void remove(Map<String, Integer> ledger, String itemId, int count) {
        int remaining = ledger.getOrDefault(itemId, 0) - count;
        if (remaining <= 0) {
            ledger.remove(itemId);
        } else {
            ledger.put(itemId, remaining);
        }
    }
}
