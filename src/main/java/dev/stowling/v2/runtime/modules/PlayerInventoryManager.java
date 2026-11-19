package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.v2.control.PlayerInventoryResponse;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class PlayerInventoryManager {
    @FunctionalInterface
    public interface Source {
        Optional<PlayerInventorySnapshot> snapshot();
    }

    private final Source source;
    private Optional<PlayerInventorySnapshot> previous = Optional.empty();
    private final Map<UUID, PlayerInventoryAccount> accounts = new LinkedHashMap<>();
    private PlayerInventorySnapshot protectedInventory;
    private final Set<UUID> abandonedAccounts = new LinkedHashSet<>();
    private final Map<String, Integer> classificationItems = new LinkedHashMap<>();
    private long revision;
    private boolean available;
    private final java.time.Clock clock;
    private PlayerInventoryAccount lateCollection;
    private java.time.Instant lateCollectionUntil;

    public PlayerInventoryManager(Source source) {
        this(source, java.time.Clock.systemUTC());
    }

    PlayerInventoryManager(Source source, java.time.Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized Optional<Observation> observe() {
        Optional<PlayerInventorySnapshot> observed = source.snapshot();
        if (observed.isEmpty()) {
            dev.stowling.v2.runtime.TaskTrace.event("INVENTORY", "inventory.observe",
                    "available=false");
            if (available) revision++;
            available = false;
            return Optional.empty();
        }
        if (!available) revision++;
        available = true;
        PlayerInventorySnapshot current = observed.orElseThrow();
        if (!previous.equals(observed)) revision++;
        initialize(current);
        boolean baseline = previous.isEmpty();
        PlayerInventoryDelta delta = previous
                .map(prior -> PlayerInventoryDelta.between(prior, current))
                .orElseGet(() -> new PlayerInventoryDelta(Map.of(), Map.of()));
        previous = Optional.of(current);
        dev.stowling.v2.runtime.TaskTrace.event("INVENTORY", "inventory.observe",
                "available=true; actual=" + current.itemCounts() + "; added=" + delta.added()
                        + "; removed=" + delta.removed() + "; expected="
                        + expectedInventory().orElseThrow().itemCounts());
        return Optional.of(new Observation(baseline, current, delta));
    }

    public synchronized void reset() {
        lateCollection = null;
        lateCollectionUntil = null;
        previous = Optional.empty();
        protectedInventory = null;
        accounts.clear();
        abandonedAccounts.clear();
        classificationItems.clear();
        available = false;
        revision++;
    }

    public synchronized void replaceProtectedIdentity(String before, String after) {
        if (protectedInventory == null || before == null || after == null
                || before.isEmpty() || before.equals(after)) {
            return;
        }
        Map<String, Integer> items = new LinkedHashMap<>(protectedInventory.itemCounts());
        int count = items.getOrDefault(before, 0);
        if (count == 0) {
            return;
        }
        if (count == 1) {
            items.remove(before);
        } else {
            items.put(before, count - 1);
        }
        if (!after.isEmpty()) {
            items.merge(after, 1, Math::addExact);
        }
        protectedInventory = new PlayerInventorySnapshot(items);
        revision++;
    }

    public synchronized void initialize(PlayerInventorySnapshot baseline) {
        if (protectedInventory == null) {
            protectedInventory = Objects.requireNonNull(baseline, "baseline");
        }
    }

    public synchronized PlayerInventoryAccount account(UUID ownerId) {
        abandonedAccounts.remove(ownerId);
        return accounts.computeIfAbsent(ownerId, PlayerInventoryAccount::new);
    }

    public synchronized void register(PlayerInventoryAccount account) {
        if (lateCollection == account) lateCollection = null;
        PlayerInventoryAccount existing = accounts.putIfAbsent(account.ownerId(), account);
        if (existing != null && existing != account) {
            throw new IllegalArgumentException("inventory account already exists");
        }
        abandonedAccounts.remove(account.ownerId());
    }

    public synchronized Optional<PlayerInventorySnapshot> expectedInventory() {
        if (protectedInventory == null) {
            return Optional.empty();
        }
        Map<String, Integer> expected = new LinkedHashMap<>(protectedInventory.itemCounts());
        classificationItems.forEach((itemId, count) -> expected.merge(itemId, count, Math::addExact));
        for (PlayerInventoryAccount account : accounts.values()) {
            account.managedItems().forEach((itemId, count) ->
                    expected.merge(itemId, count, Math::addExact)
            );
        }
        return Optional.of(new PlayerInventorySnapshot(expected));
    }

    public synchronized Map<String, Integer> unassignedItems() {
        if (previous.isEmpty() || expectedInventory().isEmpty()) {
            return Map.of();
        }
        return PlayerInventoryDelta.between(
                expectedInventory().orElseThrow(), previous.orElseThrow()
        ).added();
    }

    /** Claims collection gains, abandoned receipts, and explicit browser allocations. */
    public synchronized int claimUnexpected(
            PlayerInventoryAccount account,
            Set<String> itemFilter
    ) {
        register(account);
        int claimed = claimCollectionItems(account, itemFilter);
        protectUntrackedItems();
        for (UUID owner : Set.copyOf(abandonedAccounts)) {
            PlayerInventoryAccount abandoned = accounts.get(owner);
            for (Map.Entry<String, Integer> entry : abandoned.managedItems().entrySet()) {
                if (!TaskModuleSupport.itemMatches(entry.getKey(), itemFilter)) {
                    continue;
                }
                account.recordCollected(entry.getKey(), entry.getValue());
                abandoned.confirmDeposit("managed", Map.of(entry.getKey(), entry.getValue()),
                        Map.of());
                claimed = Math.addExact(claimed, entry.getValue());
            }
            if (abandoned.managedItems().isEmpty()) {
                abandonedAccounts.remove(owner);
                accounts.remove(owner);
            }
        }
        for (Map.Entry<String, Integer> entry : Map.copyOf(classificationItems).entrySet()) {
            if (TaskModuleSupport.itemMatches(entry.getKey(), itemFilter)) {
                account.recordCollected(entry.getKey(), entry.getValue());
                classificationItems.remove(entry.getKey());
                claimed = Math.addExact(claimed, entry.getValue());
            }
        }
        if (claimed > 0) revision++;
        protectUntrackedItems();
        return claimed;
    }

    /** Collection tasks own unprotected gains within their filter, even between entity scans. */
    public synchronized int claimCollectionItems(
            PlayerInventoryAccount account,
            Set<String> itemFilter
    ) {
        return claimUnassigned(account, key -> account.acceptsCollectionItem(key)
                && TaskModuleSupport.itemMatches(key, itemFilter));
    }

    /** Used after in-flight operations finish, when cleanup must store all unprotected items. */
    public synchronized int claimUnprotectedItems(PlayerInventoryAccount account) {
        return claimUnassigned(account, key -> true);
    }

    private int claimUnassigned(PlayerInventoryAccount account,
            java.util.function.Predicate<String> accepts) {
        register(account);
        int claimed = 0;
        for (Map.Entry<String, Integer> entry : unassignedItems().entrySet()) {
            if (accepts.test(entry.getKey())) {
                account.recordCollected(entry.getKey(), entry.getValue());
                claimed = Math.addExact(claimed, entry.getValue());
            }
        }
        if (claimed > 0) revision++;
        dev.stowling.v2.runtime.TaskTrace.event("INVENTORY", "inventory.claim",
                "claimed=" + claimed + "; managed=" + account.managedItems());
        return claimed;
    }

    /** Called while idle or after all in-flight transfers and pickups have been accounted for. */
    public synchronized void protectUntrackedItems() {
        if (!available || previous.isEmpty()) return;
        captureLateCollectionPickups();
        Map<String, Integer> managed = managedCounts();
        Map<String, Integer> protectedItems = new LinkedHashMap<>();
        Map<String, Integer> queuedBefore = Map.copyOf(classificationItems);
        Map<String, Integer> actual = previous.orElseThrow().itemCounts();
        Map<String, Integer> before = protectedInventory == null
                ? Map.of() : protectedInventory.itemCounts();
        Set<String> keys = new LinkedHashSet<>(actual.keySet());
        keys.addAll(before.keySet());
        keys.addAll(classificationItems.keySet());
        for (String key : keys) {
            int count = actual.getOrDefault(key, 0);
            int owned = managed.getOrDefault(key, 0);
            // Never use protected stock to hide a missing confirmed task receipt.
            int reserved = owned > 0 ? before.getOrDefault(key, 0)
                    : Math.min(count, before.getOrDefault(key, 0));
            int queued = Math.min(Math.max(0, count - owned - reserved),
                    classificationItems.getOrDefault(key, 0));
            if (queued > 0) classificationItems.put(key, queued);
            else classificationItems.remove(key);
            int kept = Math.max(reserved, count - owned - queued);
            if (kept > 0) protectedItems.put(key, kept);
        }
        PlayerInventorySnapshot next = new PlayerInventorySnapshot(protectedItems);
        if (!next.equals(protectedInventory) || !queuedBefore.equals(classificationItems)) revision++;
        protectedInventory = next;
    }

    /** Preserve late arrivals of recently collected identities before idle protection runs. */
    private void captureLateCollectionPickups() {
        if (lateCollection == null) return;
        if (clock.instant().isAfter(lateCollectionUntil)) {
            lateCollection = null;
            return;
        }
        Set<String> identities = lateCollection.collectedItemIds();
        for (Map.Entry<String, Integer> entry : unassignedItems().entrySet()) {
            if (!identities.contains(entry.getKey())) continue;
            accounts.putIfAbsent(lateCollection.ownerId(), lateCollection);
            lateCollection.recordCollected(entry.getKey(), entry.getValue());
            abandonedAccounts.add(lateCollection.ownerId());
            lateCollectionUntil = clock.instant().plusSeconds(2);
            revision++;
        }
    }

    public synchronized PlayerInventoryResponse view(boolean busy, boolean classificationAvailable,
            Map<String, String> names) {
        Map<String, Integer> managed = managedCounts();
        Map<String, Integer> protectedItems = protectedInventory == null
                ? Map.of() : protectedInventory.itemCounts();
        var items = previous.stream().flatMap(snapshot -> snapshot.itemCounts().entrySet().stream())
                .sorted(Map.Entry.comparingByKey()).map(entry -> {
                    String key = entry.getKey();
                    int count = entry.getValue();
                    int taskCount = Math.min(count, managed.getOrDefault(key, 0));
                    int queued = Math.min(count - taskCount,
                            classificationItems.getOrDefault(key, 0));
                    int kept = Math.min(count - taskCount - queued,
                            protectedItems.getOrDefault(key, 0));
                    return new PlayerInventoryResponse.Item(key, ItemIdentity.base(key),
                            names.getOrDefault(key, ItemIdentity.base(key)), count,
                            kept, taskCount, queued);
                }).toList();
        return new PlayerInventoryResponse(Long.toString(revision), available, busy,
                classificationAvailable, items);
    }

    public synchronized void allocate(String expectedRevision, String key, int keepCount) {
        if (!Long.toString(revision).equals(expectedRevision)) {
            throw new IllegalStateException("背包已变化，请刷新后重新分配");
        }
        if (!available || previous.isEmpty()
                || !previous.orElseThrow().itemCounts().containsKey(key)) {
            throw new IllegalArgumentException("背包中没有这个物品，请刷新");
        }
        int count = previous.orElseThrow().itemCounts().get(key);
        int free = Math.max(0, count - managedCounts().getOrDefault(key, 0));
        if (keepCount < 0 || keepCount > free) {
            throw new IllegalArgumentException("保留数量必须在 0 与可分配数量之间");
        }
        Map<String, Integer> kept = new LinkedHashMap<>(protectedInventory.itemCounts());
        if (keepCount > 0) kept.put(key, keepCount);
        else kept.remove(key);
        if (free > keepCount) classificationItems.put(key, free - keepCount);
        else classificationItems.remove(key);
        protectedInventory = new PlayerInventorySnapshot(kept);
        revision++;
    }

    private Map<String, Integer> managedCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        accounts.values().forEach(account -> account.managedItems().forEach((key, count) ->
                result.merge(key, count, Math::addExact)));
        return result;
    }

    public synchronized void closeAccount(PlayerInventoryAccount account) {
        if (account.collecting() && !account.collectedItemIds().isEmpty()) {
            lateCollection = account;
            lateCollectionUntil = clock.instant().plusSeconds(2);
        }
        if (account.managedItems().isEmpty()) {
            accounts.remove(account.ownerId(), account);
        } else {
            abandonedAccounts.add(account.ownerId());
        }
        revision++;
    }

    public synchronized boolean needsRecovery() {
        return !abandonedAccounts.isEmpty() || !classificationItems.isEmpty();
    }

    public synchronized boolean hasClassificationRequests() {
        return !classificationItems.isEmpty();
    }

    public synchronized void releaseToPlayer(
            PlayerInventoryAccount account,
            Map<String, Integer> delivered
    ) {
        if (accounts.get(account.ownerId()) != account) {
            throw new IllegalArgumentException("inventory account is not registered");
        }
        delivered = new PlayerInventorySnapshot(delivered).itemCounts();
        Map<String, Integer> protectedItems = new LinkedHashMap<>(
                Optional.ofNullable(protectedInventory).orElseThrow().itemCounts()
        );
        for (Map.Entry<String, Integer> entry : delivered.entrySet()) {
            int count = entry.getValue();
            if (count <= 0 || account.managedItems().getOrDefault(entry.getKey(), 0) < count) {
                throw new IllegalArgumentException("delivery exceeds owned inventory");
            }
        }
        delivered.forEach((itemId, count) -> protectedItems.merge(itemId, count, Math::addExact));
        account.confirmDeposit("managed", delivered, Map.of());
        protectedInventory = new PlayerInventorySnapshot(protectedItems);
        revision++;
    }

    public synchronized void recordWithdrawn(
            PlayerInventoryAccount account,
            Map<String, Integer> items
    ) {
        register(account);
        Map<String, Integer> checked = new PlayerInventorySnapshot(items).itemCounts();
        checked.forEach(account::recordWithdrawn);
        revision++;
    }

    public synchronized Optional<PlayerInventorySnapshot> current() {
        return previous;
    }

    /** Only call at a settled boundary with no placed boxes or unconfirmed transfers. */
    public synchronized Map<String, Integer> reconcileMissingItems(PlayerInventoryAccount account) {
        if (!available || previous.isEmpty() || protectedInventory == null) {
            throw new IllegalStateException("无法读取真实背包，不能核对任务余料");
        }
        if (accounts.get(account.ownerId()) != account) {
            throw new IllegalArgumentException("inventory account is not registered");
        }
        Map<String, Integer> reserved = new LinkedHashMap<>(protectedInventory.itemCounts());
        classificationItems.forEach((key, count) -> reserved.merge(key, count, Math::addExact));
        accounts.values().stream().filter(other -> other != account).forEach(other ->
                other.managedItems().forEach((key, count) -> reserved.merge(key, count,
                        Math::addExact)));
        Map<String, Integer> missing = new LinkedHashMap<>();
        account.managedItems().forEach((key, count) -> {
            int actual = Math.max(0, previous.orElseThrow().count(key)
                    - reserved.getOrDefault(key, 0));
            if (count > actual) missing.put(key, count - actual);
        });
        if (!missing.isEmpty()) {
            account.confirmDeposit("managed", missing, Map.of());
            revision++;
            dev.stowling.v2.runtime.TaskTrace.event("INVENTORY", "inventory.missing",
                    "已核对真实背包，移除不存在的余料账目；未记为存入或交付: " + missing);
        }
        return Map.copyOf(missing);
    }

    public record Observation(
            boolean baseline,
            PlayerInventorySnapshot snapshot,
            PlayerInventoryDelta delta
    ) {
    }
}
