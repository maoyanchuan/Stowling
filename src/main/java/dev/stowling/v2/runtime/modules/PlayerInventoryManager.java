package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySlot;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.v2.control.PlayerInventoryResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

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
    private PlayerInventorySnapshot savedProtection = new PlayerInventorySnapshot(Map.of());
    private Consumer<PlayerInventorySnapshot> saveProtection = snapshot -> {};
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

    public synchronized void configureProtection(PlayerInventorySnapshot saved,
            Consumer<PlayerInventorySnapshot> save) {
        savedProtection = Objects.requireNonNull(saved, "saved");
        saveProtection = Objects.requireNonNull(save, "save");
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
        if (protectedInventory == null) restoreProtection(current);
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

    /** Records a ground pickup at an enabled collection point. */
    public synchronized void recordPickup(UUID collectionTask, String key, int count) {
        if (count <= 0) throw new IllegalArgumentException("pickup count must be positive");
        if (!collectionTasks.contains(collectionTask)) return;
        if (previous.isEmpty() && observe().isEmpty()) return;
        receipts.receive(collectionTask, key, count);
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
                input.managedItems().forEach((key, count) ->
                        classificationItems.merge(key, count, Math::addExact));
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
        receipts.settle(delta, classificationItems, (collectionTask, key, count) -> {
            inputFor(collectionTask).recordManagedAcquired(key, count);
            revision++;
        });
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
                updateProtection(protectedSnapshot(protectedInventory.itemCounts(), observed));
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
            updateProtection(protectedSnapshot(kept, observed));
            revision++;
        }
    }

    private PlayerInventorySnapshot protectedSnapshot(Map<String, Integer> items,
            PlayerInventorySnapshot observed) {
        Map<String, String> previousIdentities = protectedInventory == null
                ? Map.of() : protectedInventory.pickaxeIdentities();
        Map<String, String> previousPersistent = protectedInventory == null
                ? Map.of() : protectedInventory.persistentIdentities();
        Map<String, String> pickaxes = new LinkedHashMap<>();
        Map<String, String> persistent = new LinkedHashMap<>();
        for (String key : items.keySet()) {
            String identity = observed.pickaxeIdentities().getOrDefault(key,
                    previousIdentities.get(key));
            if (identity != null) pickaxes.put(key, identity);
            String stableKey = observed.persistentIdentities().getOrDefault(key,
                    previousPersistent.get(key));
            if (stableKey != null) persistent.put(key, stableKey);
        }
        return new PlayerInventorySnapshot(items, pickaxes, persistent);
    }

    private void restoreProtection(PlayerInventorySnapshot observed) {
        Map<String, Integer> kept = new LinkedHashMap<>();
        Map<String, Integer> stored = new LinkedHashMap<>(savedProtection.itemCounts());
        Map<String, Integer> remaining = new LinkedHashMap<>(stored);
        Map<String, String> pickaxes = new LinkedHashMap<>(savedProtection.pickaxeIdentities());
        Set<String> matched = new LinkedHashSet<>();
        for (String key : observed.itemCounts().keySet()) {
            String stableKey = observed.persistentKey(key);
            int count = remaining.getOrDefault(stableKey, 0);
            int reserved = Math.min(observed.count(key), count);
            if (reserved == 0) continue;
            matched.add(stableKey);
            kept.put(key, reserved);
            remaining.put(stableKey, count - reserved);
        }
        for (String oldKey : savedProtection.itemCounts().keySet()) {
            if (matched.contains(oldKey)) continue;
            String wearIdentity = pickaxes.get(oldKey);
            var candidates = observed.itemCounts().keySet().stream()
                    .filter(key -> !kept.containsKey(key))
                    .filter(key -> !stored.containsKey(observed.persistentKey(key)))
                    .filter(key -> matchesSavedVariant(oldKey, wearIdentity, key, observed))
                    .limit(2).toList();
            if (candidates.size() != 1) continue;
            String key = candidates.getFirst();
            String stableKey = observed.persistentKey(key);
            int count = stored.remove(oldKey);
            kept.put(key, Math.min(count, observed.count(key)));
            stored.put(stableKey, count);
            pickaxes.remove(oldKey);
            String newWearIdentity = observed.pickaxeIdentities().get(key);
            if (newWearIdentity != null) pickaxes.put(stableKey, newWearIdentity);
        }
        persistProtection(stored, pickaxes);
        initialize(protectedSnapshot(kept, observed));
    }

    private static boolean matchesSavedVariant(String oldKey, String wearIdentity, String key,
            PlayerInventorySnapshot observed) {
        String currentWear = observed.pickaxeIdentities().get(key);
        if (wearIdentity != null && wearIdentity.equals(currentWear)) return true;
        // Old session keys identify a type and box category, but contain no component data.
        return isSessionKey(oldKey) && observed.persistentIdentities().containsKey(key)
                && ItemIdentity.base(oldKey).equals(ItemIdentity.base(key))
                && ItemIdentity.category(oldKey).equals(ItemIdentity.category(key))
                && (wearIdentity == null || currentWear != null && isSessionKey(wearIdentity));
    }

    private static boolean isSessionKey(String key) {
        return key.startsWith("#s", ItemIdentity.base(key).length());
    }

    synchronized void initialize(PlayerInventorySnapshot baseline) {
        if (protectedInventory == null) {
            protectedInventory = Objects.requireNonNull(baseline, "baseline");
        }
    }

    private void updateProtection(PlayerInventorySnapshot next) {
        if (next.equals(protectedInventory)) return;
        Map<String, Integer> stored = new LinkedHashMap<>(savedProtection.itemCounts());
        Map<String, String> pickaxes = new LinkedHashMap<>(savedProtection.pickaxeIdentities());
        for (String key : protectedInventory.itemCounts().keySet()) {
            String stableKey = protectedInventory.persistentKey(key);
            stored.remove(stableKey);
            pickaxes.remove(stableKey);
        }
        Map<String, Integer> reserved = new LinkedHashMap<>();
        next.itemCounts().forEach((key, count) ->
                reserved.merge(next.persistentKey(key), count, Math::addExact));
        stored.putAll(reserved);
        next.pickaxeIdentities().forEach((key, identity) ->
                pickaxes.put(next.persistentKey(key), identity));
        persistProtection(stored, pickaxes);
        protectedInventory = next;
    }

    private void persistProtection(Map<String, Integer> stored, Map<String, String> pickaxes) {
        var snapshot = new PlayerInventorySnapshot(stored, pickaxes);
        if (!snapshot.equals(savedProtection)) {
            saveProtection.accept(snapshot);
            savedProtection = snapshot;
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

    public synchronized Set<String> protectedItemKeys() {
        return protectedInventory == null ? Set.of() : protectedInventory.itemCounts().keySet();
    }

    public synchronized Map<String, Integer> protectedItemCounts() {
        return protectedInventory == null ? Map.of() : protectedInventory.itemCounts();
    }

    public synchronized Set<String> availableProtectedFoodKeys(String itemId) {
        if (!available || protectedInventory == null) {
            return Set.of();
        }
        var actual = previous.orElseThrow();
        var managed = managedCounts();
        Set<String> keys = new LinkedHashSet<>();
        for (String key : protectedInventory.itemCounts().keySet()) {
            if (itemId.equals(ItemIdentity.base(key))
                    && actual.count(key) > managed.getOrDefault(key, 0)) {
                keys.add(key);
            }
        }
        return Set.copyOf(keys);
    }

    synchronized void reserveAcquiredFood(Map<String, Integer> items) {
        if (items.isEmpty()) {
            return;
        }
        Map<String, Integer> kept = new LinkedHashMap<>(protectedInventory.itemCounts());
        items.forEach((key, count) -> kept.merge(key, count, Math::addExact));
        updateProtection(protectedSnapshot(kept, previous.orElseThrow()));
        revision++;
    }

    synchronized void consumeProtectedFood(String key, int count) {
        if (protectedInventory == null || protectedInventory.count(key) == 0 || count <= 0) {
            return;
        }
        Map<String, Integer> kept = new LinkedHashMap<>(protectedInventory.itemCounts());
        int remaining = protectedInventory.count(key) - count;
        if (remaining > 0) {
            kept.put(key, remaining);
        } else {
            kept.remove(key);
        }
        updateProtection(protectedSnapshot(kept, previous.orElseThrow()));
        revision++;
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

    /** Claims collection gains, abandoned receipts, and items queued for classification. */
    public synchronized int claimUnexpected(
            PlayerInventoryAccount account,
            Set<String> itemFilter
    ) {
        register(account);
        int claimed = claimCollectionItems(account, itemFilter);
        reconcileUnassignedItems();
        for (UUID owner : Set.copyOf(abandonedAccounts)) {
            PlayerInventoryAccount abandoned = accounts.get(owner);
            claimed += transferToCollection(abandoned, account, itemFilter);
            if (abandoned.managedItems().isEmpty()) {
                abandonedAccounts.remove(owner);
                accounts.remove(owner);
            }
        }
        if (account.acceptsClassification()) {
            for (Map.Entry<String, Integer> entry : Map.copyOf(classificationItems).entrySet()) {
                if (TaskModuleSupport.itemMatches(entry.getKey(), itemFilter)) {
                    account.recordCollected(entry.getKey(), entry.getValue());
                    classificationItems.remove(entry.getKey());
                    receipts.forgetQueuedGain(entry.getKey());
                    claimed = Math.addExact(claimed, entry.getValue());
                }
            }
        }
        if (claimed > 0) revision++;
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

    /** Borrows at most one requested stack from this warehouse's input to recover its placed box. */
    public synchronized int claimRecoveryInput(PlayerInventoryAccount account, String key,
            int stackCount) {
        register(account);
        PlayerInventoryAccount input = inputs.get(account.recoveryInputTask());
        if (input == null) return 0;
        reconcileMissingItems(input);
        int needed = Math.max(0, stackCount - account.managedItems().getOrDefault(key, 0));
        if (needed == 0 || input.managedItems().getOrDefault(key, 0) < needed) return 0;
        int claimed = needed;
        account.recordWithdrawn(key, claimed);
        input.confirmDeposit("managed", Map.of(key, claimed), Map.of());
        if (input.managedItems().isEmpty()) {
            accounts.remove(input.ownerId());
            inputs.remove(account.recoveryInputTask());
        }
        revision++;
        return claimed;
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

    /** Pickup unloading owns every physically present unprotected item at a settled boundary. */
    synchronized int claimAllUnprotectedForCollection(PlayerInventoryAccount account) {
        register(account);
        reconcileMissingItems(account);
        int claimed = claimCollectionItems(account, Set.of());
        reconcileUnassignedItems();
        for (var entry : Map.copyOf(classificationItems).entrySet()) {
            account.recordCollected(entry.getKey(), entry.getValue());
            classificationItems.remove(entry.getKey());
            receipts.claimQueuedGain(entry.getKey(), entry.getValue());
            claimed = Math.addExact(claimed, entry.getValue());
        }
        Map<String, Integer> available = new LinkedHashMap<>();
        previous.orElseThrow().itemCounts().forEach((key, count) -> available.put(key,
                Math.max(0, count - protectedInventory.count(key)
                        - account.managedItems().getOrDefault(key, 0))));
        for (PlayerInventoryAccount other : List.copyOf(accounts.values())) {
            if (other == account) continue;
            for (var entry : other.managedItems().entrySet()) {
                String key = entry.getKey();
                int count = Math.min(entry.getValue(), available.getOrDefault(key, 0));
                if (count <= 0) continue;
                other.confirmDeposit("managed", Map.of(key, count), Map.of());
                account.recordCollected(key, count);
                available.put(key, available.get(key) - count);
                claimed = Math.addExact(claimed, count);
            }
        }
        inputs.entrySet().removeIf(entry -> {
            PlayerInventoryAccount input = entry.getValue();
            if (!input.managedItems().isEmpty()) return false;
            accounts.remove(input.ownerId(), input);
            return true;
        });
        for (UUID owner : Set.copyOf(abandonedAccounts)) {
            PlayerInventoryAccount abandoned = accounts.get(owner);
            if (abandoned != null && !abandoned.managedItems().isEmpty()) continue;
            abandonedAccounts.remove(owner);
            if (abandoned != null) accounts.remove(owner, abandoned);
        }
        if (claimed > 0) revision++;
        return claimed;
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
    public synchronized void reconcileUnassignedItems() {
        if (!available || previous.isEmpty()) return;
        captureLateCollectionPickups();
        Map<String, Integer> managed = managedCounts();
        Map<String, Integer> protectedItems = new LinkedHashMap<>();
        Map<String, Integer> queuedItems = new LinkedHashMap<>();
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
            int queued = Math.max(0, count - owned - reserved);
            if (queued > 0) queuedItems.put(key, queued);
            if (reserved > 0) protectedItems.put(key, reserved);
        }
        PlayerInventorySnapshot next = protectedSnapshot(protectedItems, previous.orElseThrow());
        boolean changed = !next.equals(protectedInventory)
                || !queuedItems.equals(classificationItems);
        updateProtection(next);
        if (!collectionTasks.isEmpty()) {
            Instant now = clock.instant();
            receipts.expireQueuedGains(now);
            for (String key : keys) {
                int change = queuedItems.getOrDefault(key, 0)
                        - classificationItems.getOrDefault(key, 0);
                receipts.automaticQueueChange(key, change, now);
            }
        }
        classificationItems.clear();
        classificationItems.putAll(queuedItems);
        if (changed) revision++;
    }

    /** Preserve late arrivals of recently collected identities before queuing the remainder. */
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
        return view(busy, classificationAvailable, names, List.of());
    }

    public synchronized PlayerInventoryResponse view(boolean busy, boolean classificationAvailable,
            Map<String, String> names, List<PlayerInventorySlot> slots) {
        Map<String, Integer> managed = managedCounts();
        Map<String, Integer> protectedItems = protectedInventory == null
                ? Map.of() : protectedInventory.itemCounts();
        var items = previous.stream().flatMap(snapshot -> snapshot.itemCounts().entrySet().stream())
                .sorted(Map.Entry.comparingByKey()).map(entry -> {
                    String key = entry.getKey();
                    int count = entry.getValue();
                    int taskCount = Math.min(count, managed.getOrDefault(key, 0));
                    int kept = Math.min(count - taskCount, protectedItems.getOrDefault(key, 0));
                    int queued = Math.min(count - taskCount - kept,
                            classificationItems.getOrDefault(key, 0));
                    return new PlayerInventoryResponse.Item(key, ItemIdentity.base(key),
                            names.getOrDefault(key, ItemIdentity.base(key)), count,
                            kept, taskCount, queued);
                }).toList();
        return new PlayerInventoryResponse(Long.toString(revision), available, busy,
                classificationAvailable, items, available ? slots : List.of());
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
        updateProtection(protectedSnapshot(kept, previous.orElseThrow()));
        receipts.forgetQueuedGain(key);
        if (free > keepCount) classificationItems.put(key, free - keepCount);
        else classificationItems.remove(key);
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

    public synchronized boolean hasAbandonedAccounts() {
        return !abandonedAccounts.isEmpty();
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

    /** Returns inline classification cargo to its collecting task for shared cleanup. */
    public synchronized void transferManaged(
            PlayerInventoryAccount from, PlayerInventoryAccount to) {
        register(from);
        register(to);
        Map<String, Integer> items = from.managedItems();
        items.forEach(to::recordWithdrawn);
        from.confirmDeposit("managed", items, Map.of());
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
