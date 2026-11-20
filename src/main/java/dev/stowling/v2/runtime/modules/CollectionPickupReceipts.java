package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Pairs asynchronous take packets with real slot quantities, in either arrival order. */
final class CollectionPickupReceipts {
    private static final int SLOT_SYNC_SECONDS = 5;
    private final Clock clock;
    private final List<Receipt> incoming = new ArrayList<>();
    private final Map<String, PersonalGain> recentGains = new LinkedHashMap<>();

    CollectionPickupReceipts(Clock clock) {
        this.clock = clock;
    }

    interface Confirmation {
        void record(UUID task, String key, int count);
    }

    private record Receipt(UUID task, String key, int count, Instant expires) {}
    private record PersonalGain(int count, Instant expires) {}

    boolean hasPending() {
        return !incoming.isEmpty();
    }

    int pendingCount(UUID task) {
        return incoming.stream().filter(receipt -> receipt.task().equals(task))
                .mapToInt(Receipt::count).sum();
    }

    void receive(UUID task, String key, int count) {
        incoming.add(new Receipt(task, key, count, clock.instant().plusSeconds(SLOT_SYNC_SECONDS)));
    }

    void retain(Set<UUID> enabled) {
        incoming.removeIf(receipt -> !enabled.contains(receipt.task()));
        if (enabled.isEmpty()) recentGains.clear();
    }

    void forgetPersonalGain(String key) {
        recentGains.remove(key);
    }

    void automaticReservation(String key, int change, Instant now) {
        if (change == 0) return;
        PersonalGain prior = recentGains.get(key);
        int count = change + (prior == null ? 0 : prior.count());
        if (count <= 0) recentGains.remove(key);
        else recentGains.put(key, new PersonalGain(count,
                change > 0 ? now.plusSeconds(SLOT_SYNC_SECONDS) : prior.expires()));
    }

    void expirePersonalGains(Instant now) {
        recentGains.values().removeIf(gain -> now.isAfter(gain.expires()));
    }

    void settle(PlayerInventoryDelta difference, Map<String, Integer> kept, Confirmation confirm) {
        Map<String, Integer> free = new LinkedHashMap<>(difference.added());
        Instant now = clock.instant();
        var iterator = incoming.listIterator();
        while (iterator.hasNext()) {
            Receipt receipt = iterator.next();
            String key = receipt.key();
            int available = free.getOrDefault(key, 0);
            int matched = Math.min(receipt.count(), available);
            free.put(key, available - matched);
            matched += releasePersonalGain(key, receipt.count() - matched,
                    difference.removed().getOrDefault(key, 0), kept, now);
            if (matched > 0) confirm.record(receipt.task(), key, matched);
            if (matched == receipt.count() || now.isAfter(receipt.expires())) iterator.remove();
            else if (matched > 0) iterator.set(new Receipt(receipt.task(), key,
                    receipt.count() - matched, receipt.expires()));
        }
    }

    // Only the automatic increase can be reclassified. Opening stock and manual allocations
    // remain personal even if a packet for the same exact key precedes its slot update.
    private int releasePersonalGain(String key, int requested, int missing,
            Map<String, Integer> kept, Instant now) {
        PersonalGain gain = recentGains.get(key);
        if (requested == 0 || gain == null || now.isAfter(gain.expires())) return 0;
        int reserved = kept.getOrDefault(key, 0);
        int count = Math.min(requested, Math.min(gain.count(), reserved) - missing);
        if (count <= 0) return 0;
        if (reserved == count) kept.remove(key);
        else kept.put(key, reserved - count);
        if (gain.count() == count) recentGains.remove(key);
        else recentGains.put(key, new PersonalGain(gain.count() - count, gain.expires()));
        return count;
    }
}
