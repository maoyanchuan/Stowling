package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
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

    public PlayerInventoryManager(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public synchronized Optional<Observation> observe() {
        Optional<PlayerInventorySnapshot> observed = source.snapshot();
        if (observed.isEmpty()) {
            return Optional.empty();
        }
        PlayerInventorySnapshot current = observed.orElseThrow();
        initialize(current);
        boolean baseline = previous.isEmpty();
        PlayerInventoryDelta delta = previous
                .map(prior -> PlayerInventoryDelta.between(prior, current))
                .orElseGet(() -> new PlayerInventoryDelta(Map.of(), Map.of()));
        previous = Optional.of(current);
        return Optional.of(new Observation(baseline, current, delta));
    }

    public synchronized void reset() {
        previous = Optional.empty();
        protectedInventory = null;
        accounts.clear();
        abandonedAccounts.clear();
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

    public synchronized int claimUnexpected(
            PlayerInventoryAccount account,
            Set<String> itemFilter
    ) {
        register(account);
        int claimed = 0;
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
        for (Map.Entry<String, Integer> entry : unassignedItems().entrySet()) {
            if (TaskModuleSupport.itemMatches(entry.getKey(), itemFilter)) {
                account.recordCollected(entry.getKey(), entry.getValue());
                claimed = Math.addExact(claimed, entry.getValue());
            }
        }
        return claimed;
    }

    public synchronized void closeAccount(PlayerInventoryAccount account) {
        if (account.managedItems().isEmpty()) {
            accounts.remove(account.ownerId(), account);
        } else {
            abandonedAccounts.add(account.ownerId());
        }
    }

    public synchronized boolean needsRecovery() {
        return !abandonedAccounts.isEmpty() || !unassignedItems().isEmpty();
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
    }

    public synchronized void recordWithdrawn(
            PlayerInventoryAccount account,
            Map<String, Integer> items
    ) {
        register(account);
        Map<String, Integer> checked = new PlayerInventorySnapshot(items).itemCounts();
        checked.forEach(account::recordWithdrawn);
    }

    public synchronized Optional<PlayerInventorySnapshot> current() {
        return previous;
    }

    public record Observation(
            boolean baseline,
            PlayerInventorySnapshot snapshot,
            PlayerInventoryDelta delta
    ) {
    }
}
