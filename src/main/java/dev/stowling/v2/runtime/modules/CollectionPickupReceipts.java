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
    private final Map<String, QueuedGain> recentGains = new LinkedHashMap<>();

    CollectionPickupReceipts(Clock clock) {
        this.clock = clock;
    }

    interface Confirmation {
        void record(UUID collectionTask, String key, int count);
    }

    private record Receipt(UUID collectionTask, String key, int count, Instant expires) {}
    private record QueuedGain(int count, Instant expires) {}

    boolean hasPending() {
        return !incoming.isEmpty();
    }

    int pendingCount(UUID task) {
        if (task == null) return 0;
        return incoming.stream().filter(receipt -> task.equals(receipt.collectionTask()))
                .mapToInt(Receipt::count).sum();
    }

    void receive(UUID task, String key, int count) {
        incoming.add(new Receipt(task, key, count, clock.instant().plusSeconds(SLOT_SYNC_SECONDS)));
    }

    void retain(Set<UUID> collectionTasks) {
        incoming.removeIf(receipt -> !collectionTasks.contains(receipt.collectionTask()));
        if (collectionTasks.isEmpty()) recentGains.clear();
    }

    void forgetQueuedGain(String key) {
        recentGains.remove(key);
    }

    void automaticQueueChange(String key, int change, Instant now) {
        if (change == 0) return;
        QueuedGain prior = recentGains.get(key);
        int count = change + (prior == null ? 0 : prior.count());
        if (count <= 0) recentGains.remove(key);
        else recentGains.put(key, new QueuedGain(count,
                change > 0 ? now.plusSeconds(SLOT_SYNC_SECONDS) : prior.expires()));
    }

    void expireQueuedGains(Instant now) {
        recentGains.values().removeIf(gain -> now.isAfter(gain.expires()));
    }

    void settle(PlayerInventoryDelta difference, Map<String, Integer> queued,
            Confirmation confirm) {
        Map<String, Integer> free = new LinkedHashMap<>(difference.added());
        Instant now = clock.instant();
        var iterator = incoming.listIterator();
        while (iterator.hasNext()) {
            Receipt receipt = iterator.next();
            String key = receipt.key();
            int available = free.getOrDefault(key, 0);
            int matched = Math.min(receipt.count(), available);
            free.put(key, available - matched);
            matched += releaseQueuedGain(key, receipt.count() - matched,
                    difference.removed().getOrDefault(key, 0), queued, now);
            if (matched > 0) confirm.record(receipt.collectionTask(), key, matched);
            if (matched == receipt.count() || now.isAfter(receipt.expires())) iterator.remove();
            else if (matched > 0) iterator.set(new Receipt(receipt.collectionTask(), key,
                    receipt.count() - matched, receipt.expires()));
        }
    }

    // A late receipt can claim only a recent automatic increase in the classification queue.
    private int releaseQueuedGain(String key, int requested, int missing,
            Map<String, Integer> queued, Instant now) {
        QueuedGain gain = recentGains.get(key);
        if (requested == 0 || gain == null || now.isAfter(gain.expires())) return 0;
        int waiting = queued.getOrDefault(key, 0);
        int count = Math.min(requested, Math.min(gain.count(), waiting - missing));
        if (count <= 0) return 0;
        if (waiting == count) queued.remove(key);
        else queued.put(key, waiting - count);
        if (gain.count() == count) recentGains.remove(key);
        else recentGains.put(key, new QueuedGain(gain.count() - count, gain.expires()));
        return count;
    }
}
