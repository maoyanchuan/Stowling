package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class PlayerInventoryManagerTest {
    @Test
    void firstObservationEstablishesBaselineWithoutChanges() {
        PlayerInventoryManager manager = manager(
                Map.of("minecraft:iron_ingot", 4)
        );

        PlayerInventoryManager.Observation observation =
                manager.observe().orElseThrow();

        assertTrue(observation.baseline());
        assertEquals(Map.of("minecraft:iron_ingot", 4), observation.snapshot()
                .itemCounts());
        assertFalse(observation.delta().changed());
    }

    @Test
    void calculatesExactAdditionsAndRemovals() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.set(Map.of(
                "minecraft:iron_ingot", 4,
                "minecraft:gold_ingot", 2
        ));
        manager.observe().orElseThrow();
        inventory.set(Map.of(
                "minecraft:iron_ingot", 1,
                "minecraft:gold_ingot", 7,
                "minecraft:diamond", 2
        ));

        PlayerInventoryManager.Observation observation =
                manager.observe().orElseThrow();

        assertEquals(
                Map.of(
                        "minecraft:gold_ingot", 5,
                        "minecraft:diamond", 2
                ),
                observation.delta().added()
        );
        assertEquals(
                Map.of("minecraft:iron_ingot", 3),
                observation.delta().removed()
        );
    }

    @Test
    void resetStartsANewBaseline() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.set(Map.of("minecraft:iron_ingot", 4));
        manager.observe().orElseThrow();
        inventory.set(Map.of("minecraft:iron_ingot", 8));
        manager.observe().orElseThrow();

        manager.reset();
        PlayerInventoryManager.Observation observation =
                manager.observe().orElseThrow();

        assertTrue(observation.baseline());
        assertFalse(observation.delta().changed());
    }

    @Test
    void unavailableInventoryDoesNotDiscardPreviousBaseline() {
        MutableInventory inventory = new MutableInventory();
        PlayerInventoryManager manager = new PlayerInventoryManager(inventory);
        inventory.set(Map.of("minecraft:iron_ingot", 4));
        manager.observe().orElseThrow();

        inventory.unavailable();

        assertTrue(manager.observe().isEmpty());
        assertEquals(
                Map.of("minecraft:iron_ingot", 4),
                manager.current().orElseThrow().itemCounts()
        );
    }

    private static PlayerInventoryManager manager(
            Map<String, Integer> initial
    ) {
        AtomicReference<PlayerInventorySnapshot> snapshot =
                new AtomicReference<>(new PlayerInventorySnapshot(initial));
        return new PlayerInventoryManager(() ->
                Optional.ofNullable(snapshot.get()));
    }

    private static final class MutableInventory
            implements PlayerInventoryManager.Source {
        private Optional<PlayerInventorySnapshot> snapshot =
                Optional.of(new PlayerInventorySnapshot(Map.of()));

        @Override
        public Optional<PlayerInventorySnapshot> snapshot() {
            return snapshot;
        }

        private void set(Map<String, Integer> counts) {
            snapshot = Optional.of(new PlayerInventorySnapshot(counts));
        }

        private void unavailable() {
            snapshot = Optional.empty();
        }
    }
}
