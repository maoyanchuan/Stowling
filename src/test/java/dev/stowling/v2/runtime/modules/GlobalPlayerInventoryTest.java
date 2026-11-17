package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.runtime.TaskRunManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GlobalPlayerInventoryTest {
    private static final String IRON = "minecraft:iron_ingot";
    private static final String GOLD = "minecraft:gold_ingot";

    @Test
    void idleRuntimeDetectsInventoryPickupsWithoutAnyGroundItem() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        TaskModuleRuntimeRegistry registry = registry(source);
        TaskRunManager runs = new TaskRunManager(registry.navigation(), registry);
        AutoClassifyDefinition definition = new AutoClassifyDefinition(
                UUID.randomUUID(), "Recovery", true, true, false,
                "coordinate:" + UUID.randomUUID(), 2, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID(),
                        "DIRECT_BLOCK_ID", List.of(CategoryMarkerSource.PLACED_ITEM))),
                TaskPriority.NORMAL
        );
        TaskDefinition task = new TaskDefinition(definition.id(), "Recovery", List.of(
                TaskStep.action(UUID.randomUUID(), TaskModuleIds.SYNC_PLAYER_INVENTORY, Map.of())
        ));
        runs.refreshTriggers(new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(), List.of(task), List.of(), List.of(), List.of(), List.of(),
                List.of(definition)));
        runs.tick();
        assertTrue(runs.runs().isEmpty());

        source.set(Map.of(IRON, 5, GOLD, 3));
        runs.tick();

        assertEquals(1, runs.runs().size());
        assertEquals(definition.id(), runs.runs().getFirst().taskId());
        assertEquals(TaskPriority.BACKGROUND, runs.runs().getFirst().priority());
        assertEquals(Map.of(IRON, 5, GOLD, 3), registry.inventory()
                .expectedInventory().orElseThrow().itemCounts());
        assertTrue(registry.inventory().unassignedItems().isEmpty());
    }

    @Test
    void pickupsBetweenTasksKeepTheirGlobalIdentity() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        PlayerInventoryAccount collection = manager.account(UUID.randomUUID());
        manager.closeAccount(collection);
        source.set(Map.of(IRON, 5, GOLD, 3));
        manager.observe();
        PlayerInventoryAccount classification = manager.account(UUID.randomUUID());

        assertEquals(3, manager.claimUnexpected(classification, Set.of()));
        assertEquals(Map.of(GOLD, 3), classification.managedItems());
        classification.confirmDeposit("collected", Map.of(GOLD, 3), Map.of());
        source.set(Map.of(IRON, 5));
        manager.observe();
        manager.closeAccount(classification);

        assertFalse(manager.needsRecovery());
        assertEquals(Map.of(IRON, 5), manager.expectedInventory().orElseThrow().itemCounts());
    }

    @Test
    void depositAndSameItemPickupCanCancelOutWithoutLosingThePickup() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        PlayerInventoryAccount account = manager.account(UUID.randomUUID());
        source.set(Map.of(IRON, 15));
        manager.observe();
        assertEquals(10, manager.claimUnexpected(account, Set.of()));
        account.confirmDeposit("collected", Map.of(IRON, 10), Map.of());
        source.set(Map.of(IRON, 8));
        manager.observe();

        assertEquals(3, manager.claimUnexpected(account, Set.of()));
        assertEquals(Map.of(IRON, 3), account.managedItems());
        assertEquals(0, manager.claimUnexpected(account, Set.of()));
    }

    @Test
    void knownWithdrawalsAreNotClaimedByAnotherTask() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        PlayerInventoryAccount retrieval = manager.account(UUID.randomUUID());
        PlayerInventoryAccount cleanup = manager.account(UUID.randomUUID());
        source.set(Map.of(IRON, 5, GOLD, 8));
        manager.observe();
        manager.recordWithdrawn(retrieval, Map.of(GOLD, 8));

        assertEquals(0, manager.claimUnexpected(cleanup, Set.of()));
        assertTrue(cleanup.managedItems().isEmpty());
        assertEquals(Map.of(GOLD, 8), retrieval.managedItems());
    }

    @Test
    void deliveredItemsBecomeProtectedPlayerInventory() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        PlayerInventoryAccount retrieval = manager.account(UUID.randomUUID());
        manager.recordWithdrawn(retrieval, Map.of(GOLD, 8));
        source.set(Map.of(IRON, 5, GOLD, 8));
        manager.observe();

        manager.releaseToPlayer(retrieval, Map.of(GOLD, 8));
        manager.closeAccount(retrieval);
        PlayerInventoryAccount cleanup = manager.account(UUID.randomUUID());

        assertEquals(0, manager.claimUnexpected(cleanup, Set.of()));
        assertEquals(Map.of(IRON, 5, GOLD, 8), manager.expectedInventory()
                .orElseThrow().itemCounts());
        source.set(Map.of(IRON, 5, GOLD, 10));
        manager.observe();
        assertEquals(2, manager.claimUnexpected(cleanup, Set.of()));
        assertEquals(Map.of(GOLD, 2), cleanup.managedItems());
    }

    @Test
    void invalidDeliveryDoesNotChangeEitherLedger() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        PlayerInventoryAccount retrieval = manager.account(UUID.randomUUID());
        manager.recordWithdrawn(retrieval, Map.of(GOLD, 2));

        assertThrows(IllegalArgumentException.class,
                () -> manager.releaseToPlayer(retrieval, Map.of(GOLD, 3)));

        assertEquals(Map.of(GOLD, 2), retrieval.managedItems());
        assertEquals(Map.of(IRON, 5, GOLD, 2), manager.expectedInventory()
                .orElseThrow().itemCounts());
    }

    @Test
    void unfinishedAccountIsTransferredOnceInsteadOfBeingDiscarded() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        PlayerInventoryAccount failed = manager.account(UUID.randomUUID());
        manager.recordWithdrawn(failed, Map.of(GOLD, 3));
        source.set(Map.of(IRON, 5, GOLD, 3));
        manager.observe();
        manager.closeAccount(failed);
        assertTrue(manager.needsRecovery());
        PlayerInventoryAccount cleanup = manager.account(UUID.randomUUID());

        assertEquals(3, manager.claimUnexpected(cleanup, Set.of()));
        assertTrue(failed.managedItems().isEmpty());
        assertEquals(Map.of(GOLD, 3), cleanup.itemsForSource("collected"));
        assertEquals(0, manager.claimUnexpected(cleanup, Set.of()));
    }

    @Test
    void filterLeavesOtherPickupsAvailableForTheNextTask() {
        InventorySource source = new InventorySource(Map.of());
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        source.set(Map.of(IRON, 5, GOLD, 3));
        manager.observe();

        assertEquals(5, manager.claimUnexpected(manager.account(UUID.randomUUID()), Set.of(IRON)));
        assertEquals(Map.of(GOLD, 3), manager.unassignedItems());
        assertEquals(3, manager.claimUnexpected(manager.account(UUID.randomUUID()), Set.of(GOLD)));
        assertTrue(manager.unassignedItems().isEmpty());
    }

    private static TaskModuleRuntimeRegistry registry(InventorySource source) {
        NavigationPort navigation = new NavigationPort() {
            @Override
            public NavigationStatus start(BlockPosition target) {
                return NavigationStatus.ARRIVED;
            }

            @Override
            public NavigationStatus status(BlockPosition target) {
                return NavigationStatus.ARRIVED;
            }

            @Override
            public void cancel() {
            }
        };
        TaskModuleRuntimeRegistry base = TaskModuleRuntimeRegistry.unavailablePorts(navigation);
        DroppedItemObserverPort droppedItems = new DroppedItemObserverPort() {
            @Override
            public List<DroppedItem> observe(BlockPosition center, int radius) {
                return List.of();
            }

            @Override
            public List<DroppedItem> observe(RegionDefinition region) {
                return List.of();
            }

            @Override
            public Optional<Map<String, Integer>> playerItemCounts() {
                return source.snapshot().map(PlayerInventorySnapshot::itemCounts);
            }
        };
        return new TaskModuleRuntimeRegistry(navigation, droppedItems, base.containerRegions(),
                base.containerTransfers(), base.containerWithdrawals(), base.containerInspections(),
                base.categoryMarkerCache(), base.foodConsumption(), base.categoryZones(),
                StandardTaskModuleFactories.create());
    }

    private static final class InventorySource implements PlayerInventoryManager.Source {
        private PlayerInventorySnapshot current;

        private InventorySource(Map<String, Integer> counts) {
            set(counts);
        }

        private void set(Map<String, Integer> counts) {
            current = new PlayerInventorySnapshot(counts);
        }

        @Override
        public Optional<PlayerInventorySnapshot> snapshot() {
            return Optional.of(current);
        }
    }
}
