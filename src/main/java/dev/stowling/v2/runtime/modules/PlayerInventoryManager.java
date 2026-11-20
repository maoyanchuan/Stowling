package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.v2.control.PlayerInventoryResponse;
import java.time.Instant;
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
    // Confirmed inputs belong to a collection point even between runs of its task.
    private final Map<UUID, PlayerInventoryAccount> inputs = new LinkedHashMap<>();
    private final CollectionPickupReceipts receipts;
    private Set<UUID> collectionTasks = Set.of();
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
        this.receipts = new CollectionPickupReceipts(clock);
    }

    public synchronized Optional<Observation> observe() {
        Optional<PlayerInventorySnapshot> observed = source.snapshot();
        if (observed.isEmpty()) {
            dev.stowling.v2.runtime.TaskTrace.event("INVENTORY", "inventory.observe",
                    "available=false");
            if (available) {
                clearCollectionInputs();
                revision++;
            }
            available = false;
            return Optional.empty();
        }
        if (!available) revision++;
        available = true;
        PlayerInventorySnapshot current = observed.orElseThrow();
        if (!previous.equals(observed)) revision++;
        initialize(current);
        reconcilePickaxeWear(current);
        boolean baseline = previous.isEmpty();
        PlayerInventoryDelta delta = previous
                .map(prior -> PlayerInventoryDelta.between(prior, current))
                .orElseGet(() -> new PlayerInventoryDelta(Map.of(), Map.of()));
        previous = Optional.of(current);
        settlePickupReceipts();
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
        clearCollectionInputs();
        abandonedAccounts.clear();
        classificationItems.clear();
        available = false;
        revision++;
    }

    /** Called by the local player's pickup packet before its entity is removed. */
    public synchronized void recordPickup(UUID task, String key, int count) {
        if (count <= 0) throw new IllegalArgumentException("pickup count must be positive");
        if (!collectionTasks.contains(task)) return;
        if (previous.isEmpty() && observe().isEmpty()) return;
        receipts.receive(task, key, count);
    }

    public synchronized boolean hasCollectedInput(UUID task) {
        PlayerInventoryAccount input = inputs.get(task);
        return input != null && !input.managedItems().isEmpty();
    }

    synchronized int pendingPickupCount(PlayerInventoryAccount account) {
        return receipts.pendingCount(account.collectionTask());
    }

    public synchronized void retainCollectionTasks(Set<UUID> enabled) {
        collectionTasks = Set.copyOf(enabled);
        receipts.retain(enabled);
        inputs.entrySet().removeIf(entry -> {
            if (enabled.contains(entry.getKey())) return false;
            PlayerInventoryAccount input = entry.getValue();
            if (available) {
                reconcileMissingItems(input);
                releaseToPlayer(input, input.managedItems());
            }
            accounts.remove(input.ownerId());
            revision++;
            return true;
        });
    }

    private void clearCollectionInputs() {
        inputs.values().forEach(input -> accounts.remove(input.ownerId()));
        inputs.clear();
        receipts.retain(Set.of());
    }

    private void settlePickupReceipts() {
        if (!receipts.hasPending()) return;
        var delta = PlayerInventoryDelta.between(expectedInventory().orElseThrow(),
                previous.orElseThrow());
        Map<String, Integer> kept = new LinkedHashMap<>(protectedInventory.itemCounts());
        receipts.settle(delta, kept, (task, key, count) -> {
            inputFor(task).recordManagedAcquired(key, count);
            revision++;
        });
        if (!kept.equals(protectedInventory.itemCounts())) {
            protectedInventory = protectedSnapshot(kept, previous.orElseThrow());
        }
    }

    private PlayerInventoryAccount inputFor(UUID task) {
        return inputs.computeIfAbsent(task, ignored -> account(UUID.randomUUID()));
    }

    private void reconcilePickaxeWear(PlayerInventorySnapshot observed) {
        var protectedPickaxes = protectedInventory.pickaxeIdentities();
        var observedPickaxes = observed.pickaxeIdentities();
        if (observedPickaxes.isEmpty()) return;
        boolean missing = protectedPickaxes.keySet().stream()
                .anyMatch(key -> protectedInventory.count(key) > observed.count(key));
        boolean newIdentity = observedPickaxes.keySet().stream().anyMatch(key ->
                protectedInventory.count(key) > 0 && !protectedPickaxes.containsKey(key));
        if (!missing) {
            if (newIdentity) {
                protectedInventory = protectedSnapshot(protectedInventory.itemCounts(), observed);
            }
            return;
        }
        var expected = expectedInventory().orElseThrow();
        Map<String, Integer> kept = new LinkedHashMap<>(protectedInventory.itemCounts());
        boolean changed = newIdentity;
        for (var pickaxe : observedPickaxes.entrySet()) {
            String after = pickaxe.getKey();
            // Only unassigned excess can replace a missing protected pickaxe.
            int extra = observed.count(after) - expected.count(after);
            for (var original : protectedPickaxes.entrySet()) {
                if (extra <= 0) break;
                if (!pickaxe.getValue().equals(original.getValue())) continue;
                String before = original.getKey();
                int count = kept.getOrDefault(before, 0);
                int moved = Math.min(extra, count - observed.count(before));
                if (moved <= 0) continue;
                if (count == moved) kept.remove(before);
                else kept.put(before, count - moved);
                kept.merge(after, moved, Math::addExact);
                extra -= moved;
                changed = true;
            }
        }
        if (changed) {
            protectedInventory = protectedSnapshot(kept, observed);
            revision++;
        }
    }

    private PlayerInventorySnapshot protectedSnapshot(Map<String, Integer> items,
            PlayerInventorySnapshot observed) {
        Map<String, String> previousIdentities = protectedInventory == null
                ? Map.of() : protectedInventory.pickaxeIdentities();
        Map<String, String> pickaxes = new LinkedHashMap<>();
        for (String key : items.keySet()) {
            String identity = observed.pickaxeIdentities().getOrDefault(key,
                    previousIdentities.get(key));
            if (identity != null) pickaxes.put(key, identity);
        }
        return new PlayerInventorySnapshot(items, pickaxes);
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
            claimed += transferToCollection(abandoned, account, itemFilter);
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
        if (account.collectionTask() != null) {
            PlayerInventoryAccount input = inputs.get(account.collectionTask());
            if (input == null) return 0;
            reconcileMissingItems(input);
            int claimed = transferToCollection(input, account, itemFilter);
            if (input.managedItems().isEmpty()) {
                accounts.remove(input.ownerId());
                inputs.remove(account.collectionTask());
            }
            if (claimed > 0) {
                revision++;
                dev.stowling.v2.runtime.TaskTrace.event("INVENTORY", "inventory.claim",
                        "来源=收集点拾取回执；认领=" + claimed);
            }
            return claimed;
        }
        return claimUnassigned(account, key -> account.acceptsCollectionItem(key)
                && TaskModuleSupport.itemMatches(key, itemFilter));
    }

    private static int transferToCollection(PlayerInventoryAccount from, PlayerInventoryAccount to,
            Set<String> filter) {
        int claimed = 0;
        for (var entry : from.managedItems().entrySet()) {
            if (!TaskModuleSupport.itemMatches(entry.getKey(), filter)) continue;
            int count = entry.getValue();
            to.recordCollected(entry.getKey(), count);
            from.confirmDeposit("managed", Map.of(entry.getKey(), count), Map.of());
            claimed += count;
        }
        return claimed;
    }

    /** Used after in-flight operations finish, when cleanup must store all unprotected items. */
    public synchronized int claimUnprotectedItems(PlayerInventoryAccount account) {
        if (account.collectionTask() != null) return claimCollectionItems(account, Set.of());
        return claimUnassigned(account, key -> true);
    }

    public synchronized void claimForCleanup(PlayerInventoryAccount account) {
        if (account.collectionTask() == null) claimUnprotectedItems(account);
        claimUnexpected(account, Set.of());
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
        Instant now = clock.instant();
        receipts.expirePersonalGains(now);
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
            if (!collectionTasks.isEmpty()) {
                receipts.automaticReservation(key, kept - before.getOrDefault(key, 0), now);
            }
        }
        PlayerInventorySnapshot next = protectedSnapshot(protectedItems, previous.orElseThrow());
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
        receipts.forgetPersonalGain(key);
        Map<String, Integer> kept = new LinkedHashMap<>(protectedInventory.itemCounts());
        if (keepCount > 0) kept.put(key, keepCount);
        else kept.remove(key);
        if (free > keepCount) classificationItems.put(key, free - keepCount);
        else classificationItems.remove(key);
        protectedInventory = protectedSnapshot(kept, previous.orElseThrow());
        revision++;
    }

    private Map<String, Integer> managedCounts() {
        Map<String, Integer> result = new LinkedHashMap<>();
        accounts.values().forEach(account -> account.managedItems().forEach((key, count) ->
                result.merge(key, count, Math::addExact)));
        return result;
    }

    public synchronized void closeAccount(PlayerInventoryAccount account) {
        UUID task = account.collectionTask();
        if (task != null && collectionTasks.contains(task)) {
            Map<String, Integer> managed = account.managedItems();
            account.itemsForSource("collected").forEach((key, count) -> {
                int remaining = Math.min(count, managed.getOrDefault(key, 0));
                if (remaining > 0) {
                    inputFor(task).recordManagedAcquired(key, remaining);
                    account.confirmDeposit("collected", Map.of(key, remaining), Map.of());
                }
            });
        }
        if (task == null && account.collecting()
                && !account.collectedItemIds().isEmpty()) {
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
        protectedInventory = protectedSnapshot(protectedItems, previous.orElse(protectedInventory));
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
