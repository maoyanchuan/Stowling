package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.FoodStatus;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskFlowTemplates;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.model.AutoClassifyTaskFactory;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.runtime.TaskRunManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GlobalPlayerInventoryTest {
    private static final String IRON = "minecraft:iron_ingot";
    private static final String GOLD = "minecraft:gold_ingot";
    private static final String BREAD = "minecraft:bread";

    @Test
    void scheduledMealUsesProtectedStockBeforeWithdrawingAndProtectsTheRefill() {
        InventorySource source = new InventorySource(Map.of(BREAD, 1));
        MealFoodPort food = new MealFoodPort(source);
        TaskModuleRuntimeRegistry registry = registry(source, food);
        TaskRunManager runs = new TaskRunManager(registry.navigation(), registry);
        runs.tick();
        var view = registry.inventory().view(false, true, Map.of());
        registry.inventory().allocate(view.revision(), BREAD, 1);
        var chest = new CoordinatePoint(UUID.randomUUID(), "Food chest", 0, 64, 0);
        var task = new TaskDefinition(UUID.randomUUID(), "Meal", TaskPriority.URGENT, false,
                TaskFlowTemplates.scheduledEating("coordinate:" + chest.id(), BREAD, 18));

        runs.start(task, List.of(chest));
        for (int tick = 0; tick < 20 && runs.busy(); tick++) {
            runs.tick();
        }
        runs.tick();

        assertFalse(runs.busy());
        assertEquals(List.of("eat", "withdraw", "eat"), food.actions);
        assertEquals(20, food.level);
        var bread = registry.inventory().view(false, true, Map.of()).items().getFirst();
        assertEquals(4, bread.count());
        assertEquals(4, bread.protectedCount());
        assertEquals(0, bread.taskCount());
    }

    @Test
    void interruptedRecoveryIsConfirmedBeforeTheUnassignedBoxCanBecomePersonal() {
        String box = "minecraft:shulker_box#recovered";
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        TaskModuleRuntimeRegistry registry = registry(source);
        boolean[] pending = {false};
        boolean[] recovered = {false};
        registry.setStorageAutomation(new StorageAutomationPort() {
            @Override
            public boolean hasTemporaryResources(UUID owner) {
                return pending[0];
            }

            @Override
            public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                    Map<String, Integer> items) {
                throw new AssertionError("only interruption cleanup is expected");
            }

            @Override
            public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                return new TaskModuleExecutor() {
                    @Override
                    public TaskModuleResult start() {
                        return tick();
                    }

                    @Override
                    public TaskModuleResult tick() {
                        assertEquals(0, context.inventory().expectedInventory().orElseThrow()
                                .count(box));
                        context.inventory().recordWithdrawn(context.workspace().inventoryAccount(),
                                Map.of(box, 1));
                        assertEquals(1, context.inventory().expectedInventory().orElseThrow()
                                .count(box));
                        context.workspace().consumeDeposit("managed", Map.of(box, 1), Map.of());
                        source.set(Map.of(IRON, 5));
                        pending[0] = false;
                        recovered[0] = true;
                        return TaskModuleResult.succeeded("recovered and stored");
                    }

                    @Override
                    public void cancel() {
                    }
                };
            }
        });
        TaskRunManager runs = new TaskRunManager(registry.navigation(), registry);
        runs.tick();
        var point = new CoordinatePoint(UUID.randomUUID(), "Pickup", 0, 64, 0);
        var task = new TaskDefinition(UUID.randomUUID(), "Interrupted", List.of(TaskStep.action(
                UUID.randomUUID(), TaskModuleIds.COLLECT_DROPPED_ITEMS,
                Map.of(TaskModuleIds.AREA_REF, "coordinate:" + point.id()))));
        var run = runs.start(task, List.of(point));
        runs.tick();
        pending[0] = true;
        source.set(Map.of(IRON, 5, box, 1));

        runs.cancel(run.runId());
        for (int tick = 0; tick < 10 && runs.busy(); tick++) runs.tick();

        assertTrue(recovered[0]);
        assertFalse(runs.busy());
    }

    @Test
    void toolGivenImmediatelyBeforeStartingATaskIsNotClaimedByItsSyncStep() {
        InventorySource source = new InventorySource(Map.of());
        TaskModuleRuntimeRegistry registry = registry(source);
        TaskRunManager runs = new TaskRunManager(registry.navigation(), registry);
        runs.tick();
        source.set(Map.of("minecraft:diamond_pickaxe#new", 1));
        TaskDefinition task = new TaskDefinition(UUID.randomUUID(), "Sync", List.of(
                TaskStep.action(UUID.randomUUID(), TaskModuleIds.SYNC_PLAYER_INVENTORY, Map.of())));

        runs.start(task, List.of());
        for (int tick = 0; tick < 45; tick++) runs.tick();

        assertFalse(runs.busy());
        var item = runs.playerInventory().items().getFirst();
        assertEquals(0, item.protectedCount());
        assertEquals(0, item.taskCount());
        assertEquals(1, item.classificationCount());
    }

    @Test
    void browserItemsCanStartReconciliationWithOnlyBackgroundClassificationEnabled() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        TaskModuleRuntimeRegistry registry = registry(source);
        TaskRunManager runs = new TaskRunManager(registry.navigation(), registry);
        var point = new CoordinatePoint(UUID.randomUUID(), "Pickup", 0, 64, 0);
        var cache = new RegionDefinition(UUID.randomUUID(), "Cache", 0, 64, 0, 1, 64, 1);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Storage", List.of(
                new CategoryBindingDefinition(UUID.randomUUID(),
                        new AreaBounds(5, 64, 1, 5, 64, 1),
                        new AreaBounds(5, 64, 0, 5, 64, 0), "DIRECT_BLOCK_ID")));
        var definition = new AutoClassifyDefinition(UUID.randomUUID(), "Automatic",
                true, false, true, "coordinate:" + point.id(), 2, "region:" + cache.id(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + zone.id())),
                TaskPriority.NORMAL);
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point),
                AutoClassifyTaskFactory.tasks(definition, List.of()), List.of(cache),
                List.of(zone), List.of(), List.of(), List.of(definition));
        runs.tick();
        runs.allocatePlayerInventory(runs.playerInventory().revision(), IRON, 2);
        runs.refreshTriggers(config);
        assertTrue(runs.playerInventory().classificationAvailable());
        runs.tick();

        assertTrue(runs.busy());
        var item = runs.playerInventory().items().getFirst();
        assertEquals(2, item.protectedCount());
        assertEquals(3, item.taskCount());
        assertEquals(0, item.classificationCount());
        assertThrows(IllegalStateException.class,
                () -> runs.allocatePlayerInventory(runs.playerInventory().revision(), IRON, 0));
    }

    @Test
    void idleRuntimeQueuesItemsWhileClassificationIsDisabled() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        TaskModuleRuntimeRegistry registry = registry(source);
        TaskRunManager runs = new TaskRunManager(registry.navigation(), registry);
        AutoClassifyDefinition definition = new AutoClassifyDefinition(
                UUID.randomUUID(), "Recovery", true, true, false,
                "coordinate:" + UUID.randomUUID(), 2, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
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

        assertTrue(runs.runs().isEmpty());
        assertEquals(Map.of(IRON, 5, GOLD, 3), registry.inventory()
                .expectedInventory().orElseThrow().itemCounts());
        assertTrue(registry.inventory().unassignedItems().isEmpty());
        var items = runs.playerInventory().items();
        assertEquals(0, items.stream().mapToInt(item -> item.protectedCount()).sum());
        assertEquals(8, items.stream().mapToInt(item -> item.classificationCount()).sum());
    }

    @Test
    void giftsBetweenTasksAreClaimedOnceByClassification() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        manager.allocate(manager.view(false, true, Map.of()).revision(), IRON, 5);
        PlayerInventoryAccount collection = manager.account(UUID.randomUUID());
        manager.closeAccount(collection);
        source.set(Map.of(IRON, 5, GOLD, 3));
        manager.observe();
        PlayerInventoryAccount classification = manager.account(UUID.randomUUID());

        assertEquals(3, manager.claimUnexpected(classification, Set.of()));
        assertEquals(Map.of(GOLD, 3), classification.managedItems());
        assertEquals(Map.of(IRON, 5, GOLD, 3), manager.expectedInventory()
                .orElseThrow().itemCounts());
        assertEquals(0, manager.claimUnexpected(classification, Set.of()));
        classification.confirmDeposit("collected", Map.of(GOLD, 3), Map.of());
        source.set(Map.of(IRON, 5));
        manager.observe();
        manager.reconcileUnassignedItems();
        manager.closeAccount(classification);

        assertFalse(manager.needsRecovery());
        assertEquals(Map.of(IRON, 5), manager.expectedInventory().orElseThrow().itemCounts());
    }

    @Test
    void confirmedTaskWithdrawalsStaySeparateFromUntrackedSameItemAdditions() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        manager.allocate(manager.view(false, true, Map.of()).revision(), IRON, 5);
        PlayerInventoryAccount account = manager.account(UUID.randomUUID());
        source.set(Map.of(IRON, 15));
        manager.observe();
        manager.recordWithdrawn(account, Map.of(IRON, 10));
        account.confirmDeposit("carried", Map.of(IRON, 10), Map.of());
        source.set(Map.of(IRON, 8));
        manager.observe();

        assertEquals(3, manager.claimUnexpected(account, Set.of()));
        assertEquals(Map.of(IRON, 3), account.managedItems());
        assertEquals(Map.of(IRON, 8), manager.expectedInventory().orElseThrow().itemCounts());
        assertEquals(0, manager.claimUnexpected(account, Set.of()));
    }

    @Test
    void knownWithdrawalsAreNotClaimedByAnotherTask() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        manager.allocate(manager.view(false, true, Map.of()).revision(), IRON, 5);
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
    void unfinishedAccountIsTransferredOnceInsteadOfBeingDiscarded() {
        InventorySource source = new InventorySource(Map.of(IRON, 5));
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        manager.allocate(manager.view(false, true, Map.of()).revision(), IRON, 5);
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
    void filterLeavesOtherExplicitClassificationItemsForTheNextTask() {
        InventorySource source = new InventorySource(Map.of());
        PlayerInventoryManager manager = new PlayerInventoryManager(source);
        manager.observe();
        source.set(Map.of(IRON, 5, GOLD, 3));
        manager.observe();
        manager.reconcileUnassignedItems();
        manager.allocate(manager.view(false, true, Map.of()).revision(), IRON, 0);
        manager.allocate(manager.view(false, true, Map.of()).revision(), GOLD, 0);

        assertEquals(5, manager.claimUnexpected(manager.account(UUID.randomUUID()), Set.of(IRON)));
        assertEquals(3, manager.view(false, true, Map.of()).items().stream()
                .filter(item -> item.key().equals(GOLD)).findFirst().orElseThrow()
                .classificationCount());
        assertEquals(3, manager.claimUnexpected(manager.account(UUID.randomUUID()), Set.of(GOLD)));
        assertTrue(manager.unassignedItems().isEmpty());
    }

    private static TaskModuleRuntimeRegistry registry(InventorySource source) {
        return registry(source, FoodConsumptionPort.UNAVAILABLE);
    }

    private static TaskModuleRuntimeRegistry registry(InventorySource source,
            FoodConsumptionPort food) {
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
            public NavigationStatus startInteraction(BlockPosition target, AreaBounds area) {
                return NavigationStatus.ARRIVED;
            }

            @Override
            public dev.stowling.schedule.StopOutcome requestStop() {
                return dev.stowling.schedule.StopOutcome.CLEAN;
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
                base.categoryMarkerCache(), food, base.categoryZones(),
                StandardTaskModuleFactories.create());
    }

    private static final class MealFoodPort implements FoodConsumptionPort {
        private final InventorySource source;
        private final List<String> actions = new ArrayList<>();
        private int level = 12;

        private MealFoodPort(InventorySource source) {
            this.source = source;
        }

        @Override
        public FoodStatus observe() {
            return new FoodStatus(true, level, 0);
        }

        @Override
        public FoodConsumptionResult startAcquiringFood(
                ContainerTarget target, String itemId, boolean replenish
        ) {
            assertEquals(BREAD, itemId);
            assertTrue(replenish);
            assertEquals(0, source.current.count(BREAD));
            actions.add("withdraw");
            source.set(Map.of(BREAD, 5));
            return FoodConsumptionResult.pending("withdrawing");
        }

        @Override
        public FoodConsumptionResult pollAcquiringFood() {
            return FoodConsumptionResult.succeeded("withdrawn", Map.of(BREAD, 5));
        }

        @Override
        public FoodConsumptionResult startEating(String itemId) {
            throw new AssertionError("scheduled meals must use protected food");
        }

        @Override
        public FoodConsumptionResult startEating(String itemId, Set<String> allowedKeys) {
            assertEquals(BREAD, itemId);
            assertEquals(Set.of(BREAD), allowedKeys);
            actions.add("eat");
            int remaining = source.current.count(BREAD) - 1;
            source.set(remaining == 0 ? Map.of() : Map.of(BREAD, remaining));
            level += 4;
            return FoodConsumptionResult.succeeded("eaten", Map.of(BREAD, 1));
        }

        @Override
        public FoodConsumptionResult pollEating() {
            throw new AssertionError("fake eats immediately");
        }

        @Override
        public void cancelEating() {
        }
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
