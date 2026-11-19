package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.ClassificationBatchPolicy;
import dev.stowling.automation.ClassificationLayoutEntry;
import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.FoodStatus;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.client.interaction.BaritoneAutomationNavigation;
import dev.stowling.domain.BlockPosition;
import dev.stowling.navigation.NavigationService;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.StorageMode;
import dev.stowling.v2.model.StoragePolicy;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BasicModuleSemanticsTest {
    @Test
    void boxDrainsCarryTheRemainingBatchCapacityInsteadOfStartingAnotherFullBatch() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        RecordingLayoutWithdrawal withdrawal = new RecordingLayoutWithdrawal();
        TaskExecutionContext context = context(new ForbiddenNavigation(), new RecordingTransfers(),
                withdrawal, new FoodPort(), workspace);
        var drain = new ContainerBatchWithdrawal(context,
                target(0, 65, 0, "minecraft:shulker_box"), Set.of("minecraft:stone"), 2);

        assertEquals(TaskModuleState.SUCCEEDED, drain.start().state());
        assertEquals(2, withdrawal.stackLimit);
        assertEquals(Map.of("minecraft:stone", 16), drain.withdrawn());
    }

    @Test
    void cachesEmptiedByCrossCacheBoxConsolidationAreNotOpenedAgainByTheOuterLoop() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget first = target(0, 64, 0, "cache");
        ContainerTarget second = target(1, 64, 0, "cache");
        ContainerTarget third = target(2, 64, 0, "cache");
        ContainerTarget storage = target(10, 64, 0, "chest");
        workspace.setResolvedCategories(List.of(storage), Map.of(storage, Set.of("minecraft:stone")),
                List.of(first, second, third));
        workspace.selectNextContainer().orElseThrow();
        for (ContainerTarget cache : List.of(first, second, third)) {
            workspace.recordCacheContents(cache, Map.of("minecraft:shulker_box", 1));
        }

        assertFalse(workspace.hasContainers());
        assertTrue(workspace.selectNextContainer().isEmpty());
    }

    @Test
    void classificationBatchAndDeliveryFollowLayoutInsteadOfCacheOrDistanceOrder() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget cache = target(0, 64, 0, "cache");
        ContainerTarget glass = target(30, 64, 0, "chest");
        ContainerTarget dirt = target(20, 64, 0, "chest");
        ContainerTarget stone = target(1, 64, 0, "chest");
        workspace.setResolvedCategories(List.of(stone, dirt, glass), Map.of(
                stone, Set.of("minecraft:stone"), dirt, Set.of("minecraft:dirt"),
                glass, Set.of("minecraft:glass")), List.of(cache), List.of(
                new ClassificationLayoutEntry("minecraft:glass", List.of(glass)),
                new ClassificationLayoutEntry("minecraft:dirt", List.of(dirt)),
                new ClassificationLayoutEntry("minecraft:stone", List.of(stone))));
        workspace.selectNextContainer().orElseThrow();
        RecordingLayoutWithdrawal withdrawal = new RecordingLayoutWithdrawal();
        TaskExecutionContext context = context(new ForbiddenNavigation(), new RecordingTransfers(),
                withdrawal, new FoodPort(), workspace);
        var module = new WithdrawItemsModule(context, TaskStep.action(UUID.randomUUID(),
                TaskModuleIds.WITHDRAW_ITEMS, Map.of(TaskModuleIds.TARGET_REF, "output:container")));

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertEquals(List.of("minecraft:glass", "minecraft:dirt", "minecraft:stone"),
                withdrawal.order);
        assertEquals(glass, workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.consumeDeposit("carried", Map.of("minecraft:glass", 16), Map.of());
        assertEquals(dirt, workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.consumeDeposit("carried", Map.of("minecraft:dirt", 16), Map.of());
        assertEquals(stone, workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void remainingFirstItemUsesItsOtherContainerBeforeMovingToTheNextLayoutEntry() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget cache = target(0, 64, 0, "cache");
        ContainerTarget first = target(20, 64, 0, "chest");
        ContainerTarget second = target(21, 64, 0, "chest");
        ContainerTarget later = target(1, 64, 0, "chest");
        workspace.setResolvedCategories(List.of(first, second, later), Map.of(
                first, Set.of("minecraft:glass"), second, Set.of("minecraft:glass"),
                later, Set.of("minecraft:stone")), List.of(cache), List.of(
                new ClassificationLayoutEntry("minecraft:glass", List.of(first, second)),
                new ClassificationLayoutEntry("minecraft:stone", List.of(later))));
        workspace.selectNextContainer().orElseThrow();
        workspace.recordWithdrawn("minecraft:glass", 64);
        workspace.recordWithdrawn("minecraft:stone", 64);

        assertEquals(first, workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.consumeDeposit("carried", Map.of("minecraft:glass", 64),
                Map.of("minecraft:glass", 32));
        assertEquals(second, workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.markTargetUnavailable(second);
        workspace.resetStorageSelection();
        workspace.markTargetUnavailable(first);
        assertEquals(List.of("minecraft:stone"), workspace.classificationItemOrder(
                Set.of("minecraft:glass", "minecraft:stone")));
        assertEquals(later, workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void mixedBoxDrainReceivesTheSameLayoutOrderAsRegularCacheWithdrawal() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget cache = target(0, 64, 0, "cache");
        ContainerTarget storage = target(20, 64, 0, "chest");
        workspace.setResolvedCategories(List.of(storage), Map.of(storage,
                Set.of("minecraft:stone", "minecraft:glass", "minecraft:dirt")),
                List.of(cache), List.of(
                new ClassificationLayoutEntry("minecraft:glass", List.of(storage)),
                new ClassificationLayoutEntry("minecraft:dirt", List.of(storage)),
                new ClassificationLayoutEntry("minecraft:stone", List.of(storage))));
        workspace.selectNextContainer().orElseThrow();
        RecordingLayoutWithdrawal withdrawal = new RecordingLayoutWithdrawal();
        TaskExecutionContext context = context(new ForbiddenNavigation(), new RecordingTransfers(),
                withdrawal, new FoodPort(), workspace);
        var module = new ContainerBatchWithdrawal(context,
                target(0, 65, 0, "minecraft:shulker_box"),
                Set.of("minecraft:stone", "minecraft:glass", "minecraft:dirt"));

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertEquals(List.of("minecraft:glass", "minecraft:dirt", "minecraft:stone"),
                withdrawal.order);
    }

    private static final class RecordingLayoutWithdrawal implements ContainerWithdrawalPort {
        private List<String> order = List.of();
        private int stackLimit;

        @Override
        public ContainerWithdrawalResult startClassificationBatch(ContainerTarget target,
                Set<String> matchingItemIds, Set<String> fullBoxCategories, List<String> itemOrder,
                int limit) {
            stackLimit = limit;
            return startClassificationBatch(target, matchingItemIds, fullBoxCategories, itemOrder);
        }

        @Override
        public ContainerWithdrawalResult startClassificationBatch(ContainerTarget target,
                Set<String> matchingItemIds, Set<String> fullBoxCategories, List<String> itemOrder) {
            order = List.copyOf(itemOrder);
            Map<String, Integer> withdrawn = new java.util.LinkedHashMap<>();
            for (String key : matchingItemIds) withdrawn.put(key, 16);
            return ContainerWithdrawalResult.succeeded("withdrawn", withdrawn, false);
        }

        @Override
        public ContainerWithdrawalResult startWithdrawal(ContainerTarget target,
                Set<String> matchingItemIds) {
            throw new AssertionError("classification must pass the layout order");
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            throw new AssertionError("withdrawal already completed");
        }

        @Override
        public void cancelWithdrawal() {
        }
    }

    @Test
    void overflowResolvesTheCacheRegionOrLimitsTheRequestToOwnedInventory() {
        RegionDefinition region = new RegionDefinition(UUID.randomUUID(), "Cache",
                0, 64, 0, 2, 64, 2);
        List<RegionDefinition> requests = new ArrayList<>();
        StorageAutomationPort storage = new StorageAutomationPort() {
            @Override
            public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                    Map<String, Integer> items) {
                throw new AssertionError("overflow must use the packing action");
            }

            @Override
            public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                throw new AssertionError("overflow must use the packing action");
            }

            @Override
            public TaskModuleExecutor overflow(TaskExecutionContext context,
                    RegionDefinition cacheRegion) {
                requests.add(cacheRegion);
                return new TaskModuleExecutor() {
                    @Override
                    public TaskModuleResult start() {
                        return TaskModuleResult.waiting("packing");
                    }

                    @Override
                    public TaskModuleResult tick() {
                        return TaskModuleResult.succeeded("stored");
                    }

                    @Override
                    public void cancel() {
                    }
                };
            }
        };
        TaskExecutionContext base = context(new ForbiddenNavigation(), new RecordingTransfers(),
                new FailedWithdrawals(), new FoodPort(), new TaskRunWorkspace());
        TaskExecutionContext context = new TaskExecutionContext(base.navigation(),
                base.droppedItems(), base.containerRegions(), base.containerTransfers(),
                base.containerWithdrawals(), base.containerInspections(), base.categoryMarkerCache(),
                base.foodConsumption(), base.categoryZonesPort(), base.coordinates(),
                Map.of(region.id(), region), base.categoryZones(), base.workspace(),
                base.inventory(), storage);
        var action = OverflowItemsModule.create(context, TaskStep.action(UUID.randomUUID(),
                "PACK_OVERFLOW", Map.of("regionRef", "region:" + region.id())));
        assertEquals(TaskModuleState.WAITING, action.start().state());
        assertEquals(TaskModuleState.SUCCEEDED, action.tick().state());
        assertEquals(List.of(region), requests);
        OverflowItemsModule.create(context,
                TaskStep.action(UUID.randomUUID(), "PACK_OVERFLOW", Map.of()));
        assertEquals(2, requests.size());
        assertNull(requests.getLast());
    }

    @Test
    void mixedBoxContentsEnterTheExistingBatchAndRouteWithoutASecondWithdrawal() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget cache = target(0, 64, 0, "cache");
        ContainerTarget stone = target(3, 64, 0, "chest");
        ContainerTarget leaves = target(8, 64, 0, "chest");
        workspace.setResolvedCategories(List.of(stone, leaves), Map.of(
                stone, Set.of("minecraft:stone"), leaves, Set.of("minecraft:birch_leaves")),
                List.of(cache));
        workspace.selectNextContainer().orElseThrow();
        TaskExecutionContext base = context(new ForbiddenNavigation(), new RecordingTransfers(),
                new FailedWithdrawals(), new FoodPort(), workspace);
        StorageAutomationPort storage = new StorageAutomationPort() {
            @Override
            public Optional<TaskModuleExecutor> unpackMixedBox(TaskExecutionContext context,
                    ContainerTarget source) {
                assertEquals(cache, source);
                return Optional.of(new TaskModuleExecutor() {
                    @Override
                    public TaskModuleResult start() {
                        return TaskModuleResult.waiting("recovering mixed box");
                    }

                    @Override
                    public TaskModuleResult tick() {
                        context.workspace().recordWithdrawn("minecraft:stone", 32);
                        context.workspace().recordWithdrawn("minecraft:birch_leaves", 16);
                        return TaskModuleResult.succeeded("box returned");
                    }

                    @Override
                    public void cancel() {
                    }
                });
            }

            @Override
            public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                    Map<String, Integer> items) {
                throw new AssertionError("unpacking must finish before delivery");
            }

            @Override
            public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                throw new AssertionError("successful unpacking does not invoke global cleanup");
            }
        };
        var module = new WithdrawItemsModule(contextWithStorage(base, storage),
                TaskStep.action(UUID.randomUUID(), TaskModuleIds.WITHDRAW_ITEMS,
                        Map.of(TaskModuleIds.TARGET_REF, "output:container")));
        assertEquals(TaskModuleState.WAITING, module.start().state());
        assertTrue(workspace.carriedItems().isEmpty());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertEquals(Map.of("minecraft:stone", 32, "minecraft:birch_leaves", 16),
                workspace.carriedItems());
        assertTrue(workspace.cacheHasMore());
        assertEquals(stone, workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.consumeDeposit("carried", Map.of("minecraft:stone", 32), Map.of());
        assertEquals(leaves, workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void completedPartialCategoryContinuesPackingOthersBeforeAnyFallbackWithdrawal() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget cache = target(0, 64, 0, "cache");
        ContainerTarget output = target(3, 64, 0, "chest");
        List<String> categories = List.of("minecraft:cobblestone", "minecraft:granite",
                "minecraft:diorite");
        workspace.setResolvedCategories(List.of(output), Map.of(output, Set.copyOf(categories)),
                List.of(cache));
        workspace.selectNextContainer().orElseThrow();
        List<String> processed = new java.util.ArrayList<>();
        CapturingWithdrawal withdrawal = new CapturingWithdrawal();
        TaskExecutionContext base = context(new ForbiddenNavigation(), new RecordingTransfers(),
                withdrawal, new FoodPort(), workspace);
        StorageAutomationPort storage = new StorageAutomationPort() {
            @Override
            public Optional<TaskModuleExecutor> unpackMixedBox(TaskExecutionContext context,
                    ContainerTarget source) {
                String next = categories.stream().filter(context.workspace()
                        .remainingCacheCategories(source)::contains).findFirst().orElse(null);
                if (next == null) return Optional.empty();
                return Optional.of(new TaskModuleExecutor() {
                    @Override
                    public TaskModuleResult start() {
                        processed.add(next);
                        context.workspace().deferCacheCategory(next);
                        return TaskModuleResult.succeeded("已合并，未满盒留在缓存");
                    }

                    @Override
                    public TaskModuleResult tick() {
                        throw new AssertionError("a new packing stage must be started first");
                    }

                    @Override
                    public void cancel() {
                    }
                });
            }

            @Override
            public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                    Map<String, Integer> items) {
                throw new AssertionError("partials must stay in cache");
            }

            @Override
            public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                throw new AssertionError("no cleanup needed");
            }
        };
        var module = new WithdrawItemsModule(contextWithStorage(base, storage),
                TaskStep.action(UUID.randomUUID(), TaskModuleIds.WITHDRAW_ITEMS,
                        Map.of(TaskModuleIds.TARGET_REF, "output:container")));
        assertEquals(TaskModuleState.WAITING, module.start().state());
        assertEquals(TaskModuleState.WAITING, module.tick().state());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertEquals(categories, processed);
        assertTrue(withdrawal.matchingItemIds().isEmpty());
        assertTrue(workspace.carriedItems().isEmpty());
        assertTrue(!workspace.cacheHasMore());
    }

    @Test
    void mixedBoxBatchDrainPreservesConfirmedItemsOnFailureAndWaitsForSafeStop() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskExecutionContext context = context(new ForbiddenNavigation(), new RecordingTransfers(),
                new PartialWithdrawal(), new FoodPort(), workspace);
        var drain = new ContainerBatchWithdrawal(context, target(0, 64, 0, "shulker_box"),
                Set.of("minecraft:iron_ingot"));
        assertEquals(TaskModuleState.WAITING, drain.start().state());
        drain.requestStop();
        assertFalse(drain.safeToStop());
        assertEquals(TaskModuleState.FAILED, drain.tick().state());
        assertTrue(drain.safeToStop());
        assertEquals(Map.of("minecraft:iron_ingot", 2), drain.withdrawn());
        assertEquals(TaskModuleState.FAILED, drain.tick().state());
        assertEquals(Map.of("minecraft:iron_ingot", 2), workspace.carriedItems());
    }


    @Test
    void mixedStorageKeepsMultipleKindsInOneBatchWhenPartialBoxStorageIsDisabled() {
        ContainerTarget leaves = new ContainerTarget(new BlockPosition(5, 64, 0), "chest", null,
                new StoragePolicy(StorageMode.MIXED, 50, false, null));
        ContainerTarget stone = new ContainerTarget(new BlockPosition(10, 64, 0), "chest", null,
                new StoragePolicy(StorageMode.MIXED, 50, false, null));
        Set<String> dedicated = withdrawalBatchCategories(Map.of(
                leaves, Set.of("minecraft:birch_leaves"), stone, Set.of("minecraft:stone")));
        assertEquals(Set.of(), dedicated);
        ClassificationBatchPolicy batch = new ClassificationBatchPolicy(dedicated);
        batch.recordConfirmed("minecraft:birch_leaves");
        assertTrue(batch.accepts("minecraft:stone"));
        assertTrue(batch.priority("minecraft:stone") < batch.priority("minecraft:birch_leaves"));
    }

    @Test
    void aMixedAlternativePreventsAnUnnecessaryDedicatedBatch() {
        ContainerTarget mixed = new ContainerTarget(new BlockPosition(5, 64, 0), "chest", null,
                new StoragePolicy(StorageMode.MIXED, 50, false, null));
        ContainerTarget boxes = new ContainerTarget(new BlockPosition(10, 64, 0), "chest", null,
                new StoragePolicy(StorageMode.BOX_ONLY, 50, false, null));
        assertEquals(Set.of(), withdrawalBatchCategories(Map.of(
                mixed, Set.of("minecraft:stone"), boxes, Set.of("minecraft:stone"))));
    }

    @Test
    void pureBoxDestinationsAllowPartialBoxesWithoutADedicatedBatch() {
        ContainerTarget boxes = new ContainerTarget(new BlockPosition(5, 64, 0), "chest", null,
                new StoragePolicy(StorageMode.BOX_ONLY, 50, false, null));
        assertEquals(Set.of(), withdrawalBatchCategories(Map.of(
                boxes, Set.of("minecraft:stone"))));
    }

    @Test
    void pureBoxClassificationDefersTheStorageTripUntilABoxNeedsDepositing() {
        for (StorageMode mode : StorageMode.values()) {
            ContainerTarget cache = target(0, 64, 0, "cache");
            ContainerTarget storage = new ContainerTarget(new BlockPosition(50, 64, 0), "chest",
                    null, new StoragePolicy(mode, 50, false, null));
            TaskRunWorkspace workspace = new TaskRunWorkspace();
            workspace.setResolvedCategories(List.of(storage),
                    Map.of(storage, Set.of("minecraft:stone")), List.of(cache));
            workspace.recordWithdrawn("minecraft:stone", 1);
            assertEquals(storage, workspace.selectStorageTarget("carried").orElseThrow());
            RecordingMoveNavigation navigation = new RecordingMoveNavigation();
            var context = context(navigation, new RecordingTransfers(), new FailedWithdrawals(),
                    new FoodPort(), workspace);
            var move = new MoveToCoordinateModule(context, TaskStep.action(UUID.randomUUID(),
                    TaskModuleIds.MOVE_TO_COORDINATE,
                    Map.of(TaskModuleIds.TARGET_REF, "output:storage")));

            TaskModuleState result = move.start().state();
            assertEquals(mode == StorageMode.BOX_ONLY
                    ? TaskModuleState.SUCCEEDED : TaskModuleState.RUNNING, result);
            assertEquals(mode == StorageMode.BOX_ONLY ? List.of() : List.of(storage.position()),
                    navigation.containerPositions);
            assertEquals(Map.of("minecraft:stone", 1), workspace.carriedItems());
        }
    }

    private static Set<String> withdrawalBatchCategories(
            Map<ContainerTarget, Set<String>> accepted) {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(new ArrayList<>(accepted.keySet()), accepted,
                List.of(target(0, 64, 0, "cache")));
        workspace.selectNextContainer().orElseThrow();
        CapturingWithdrawal withdrawal = new CapturingWithdrawal();
        TaskExecutionContext context = context(new ForbiddenNavigation(), new RecordingTransfers(),
                withdrawal, new FoodPort(), workspace);
        var module = new WithdrawItemsModule(context, TaskStep.action(UUID.randomUUID(),
                TaskModuleIds.WITHDRAW_ITEMS, Map.of(TaskModuleIds.TARGET_REF, "output:container")));
        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        return withdrawal.fullBoxCategories;
    }

    @Test
    void classificationWaitsForBoxSupplyEvenWithoutTheCacheFallbackFlag() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget storage = target(5, 64, 5, "storage");
        ContainerTarget cache = target(0, 64, 0, "cache");
        workspace.setResolvedCategories(List.of(storage),
                Map.of(storage, Set.of("minecraft:birch_leaves")), List.of(cache));
        workspace.selectNextContainer().orElseThrow();
        workspace.recordWithdrawn("minecraft:birch_leaves", 128);
        workspace.recordWithdrawn("minecraft:stone", 64);
        TaskExecutionContext base = context(new ForbiddenNavigation(), new RecordingTransfers(),
                new FailedWithdrawals(), new FoodPort(), workspace);
        var supply = new RecordingBoxPreparation(TaskModuleResult.succeeded("box acquired"));
        TaskExecutionContext context = contextWithStorage(base, supply);
        var module = new SelectStorageTargetModule(context, TaskStep.action(UUID.randomUUID(),
                TaskModuleIds.SELECT_STORAGE_TARGET, Map.of()));

        assertEquals(TaskModuleState.WAITING, module.start().state());
        assertFalse(module.safeToStop());
        assertEquals(Map.of("minecraft:birch_leaves", 128), supply.incoming);
        assertEquals(storage, workspace.selectedStorageTarget().orElseThrow());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertTrue(module.safeToStop());
        assertEquals(List.of("supply-started", "supply-confirmed"), supply.events);
    }

    @Test
    void failedBoxSupplyDoesNotLetClassificationProceedToTheTarget() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget storage = target(5, 64, 5, "storage");
        ContainerTarget cache = target(0, 64, 0, "cache");
        workspace.setResolvedCategories(List.of(storage),
                Map.of(storage, Set.of("minecraft:birch_leaves")), List.of(cache));
        workspace.selectNextContainer().orElseThrow();
        workspace.recordWithdrawn("minecraft:birch_leaves", 128);
        TaskExecutionContext base = context(new ForbiddenNavigation(), new RecordingTransfers(),
                new FailedWithdrawals(), new FoodPort(), workspace);
        var supply = new RecordingBoxPreparation(TaskModuleResult.failed("supply unavailable"));
        var module = new SelectStorageTargetModule(contextWithStorage(base, supply),
                TaskStep.action(UUID.randomUUID(), TaskModuleIds.SELECT_STORAGE_TARGET, Map.of()));

        assertEquals(TaskModuleState.WAITING, module.start().state());
        TaskModuleResult result = module.tick();
        assertEquals(TaskModuleState.FAILED, result.state());
        assertEquals("supply unavailable", result.message());
        assertEquals(Map.of("minecraft:birch_leaves", 128), workspace.carriedItems());
    }

    private static TaskExecutionContext contextWithStorage(TaskExecutionContext base,
            StorageAutomationPort storage) {
        return new TaskExecutionContext(base.navigation(), base.droppedItems(),
                base.containerRegions(), base.containerTransfers(), base.containerWithdrawals(),
                base.containerInspections(), base.categoryMarkerCache(), base.foodConsumption(),
                base.categoryZonesPort(), base.coordinates(), base.regions(), base.categoryZones(),
                base.workspace(), base.inventory(), storage);
    }

    private static final class RecordingBoxPreparation implements StorageAutomationPort {
        private final TaskModuleResult completion;
        private final List<String> events = new ArrayList<>();
        private Map<String, Integer> incoming = Map.of();

        private RecordingBoxPreparation(TaskModuleResult completion) {
            this.completion = completion;
        }

        @Override
        public Optional<TaskModuleExecutor> prepareDeposit(TaskExecutionContext context,
                ContainerTarget target, Map<String, Integer> items) {
            incoming = Map.copyOf(items);
            return Optional.of(new TaskModuleExecutor() {
                private boolean confirmed;

                @Override
                public TaskModuleResult start() {
                    events.add("supply-started");
                    return TaskModuleResult.waiting("waiting for box supply");
                }

                @Override
                public TaskModuleResult tick() {
                    confirmed = true;
                    events.add("supply-confirmed");
                    return completion;
                }

                @Override
                public boolean safeToStop() {
                    return confirmed;
                }

                @Override
                public void cancel() {
                    events.add("supply-cancelled");
                }
            });
        }

        @Override
        public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                Map<String, Integer> items) {
            throw new AssertionError("deposit must wait for box supply");
        }

        @Override
        public TaskModuleExecutor cleanup(TaskExecutionContext context) {
            throw new AssertionError("cleanup must not run during preparation");
        }
    }

    @Test
    void completeCacheScanWaitsForAllContentsBeforeReportingSuccess() {
        ContainerTarget empty = target(1, 64, 1, "chest");
        ContainerTarget stocked = target(2, 64, 1, "chest");
        RegionDefinition region = new RegionDefinition(
                UUID.randomUUID(), "Cache", 1, 64, 1, 2, 64, 1);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskExecutionContext base = context(new ForbiddenNavigation(), new RecordingTransfers(),
                new FailedWithdrawals(), new FoodPort(), workspace);
        ContainerRegionPort scanner = new ContainerRegionPort() {
            @Override
            public ContainerScanResult scan(RegionDefinition area, Set<String> types, int volume) {
                throw new AssertionError("classification must require the complete region");
            }

            @Override
            public ContainerScanResult scanComplete(RegionDefinition area, Set<String> types,
                    int volume) {
                assertEquals(region, area);
                return ContainerScanResult.success(List.of(empty, stocked));
            }
        };
        StorageAutomationPort storage = new StorageAutomationPort() {
            @Override
            public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                    Map<String, Integer> items) {
                throw new AssertionError("inventory scan must be read-only");
            }

            @Override
            public TaskModuleExecutor cleanup(TaskExecutionContext context) {
                throw new AssertionError("inventory scan must be read-only");
            }

            @Override
            public TaskModuleExecutor scanCacheContents(TaskExecutionContext context,
                    List<ContainerTarget> containers) {
                assertEquals(List.of(empty, stocked), containers);
                return new TaskModuleExecutor() {
                    @Override
                    public TaskModuleResult start() {
                        return TaskModuleResult.waiting("reading");
                    }

                    @Override
                    public TaskModuleResult tick() {
                        context.workspace().recordCacheInventory(Map.of(
                                empty.position(), Map.of(),
                                stocked.position(), Map.of("minecraft:stone", 64)));
                        return TaskModuleResult.succeeded("complete");
                    }

                    @Override
                    public void cancel() {
                    }
                };
            }
        };
        TaskExecutionContext context = new TaskExecutionContext(base.navigation(),
                base.droppedItems(), scanner, base.containerTransfers(),
                base.containerWithdrawals(), base.containerInspections(),
                base.categoryMarkerCache(), base.foodConsumption(), base.categoryZonesPort(),
                Map.of(), Map.of(region.id(), region), Map.of(), workspace, base.inventory(),
                storage);
        var module = new ScanContainersInRegionModule(context, TaskStep.action(UUID.randomUUID(),
                TaskModuleIds.SCAN_CONTAINERS_IN_REGION,
                Map.of(TaskModuleIds.REGION_REF, "region:" + region.id(), "scanContents", "true")));

        assertEquals(TaskModuleState.WAITING, module.start().state());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        Set<String> accepted = Set.of("minecraft:stone");
        assertFalse(workspace.hasRecordedClassifiableStock(empty, accepted));
        assertTrue(workspace.hasRecordedClassifiableStock(stocked, accepted));
        workspace.recordCacheTransfer(stocked, Map.of("minecraft:stone", 64), true);
        var repeated = new ScanContainersInRegionModule(context, TaskStep.action(UUID.randomUUID(),
                TaskModuleIds.SCAN_CONTAINERS_IN_REGION,
                Map.of(TaskModuleIds.REGION_REF, "region:" + region.id(), "scanContents", "true")));
        assertEquals(TaskModuleState.SUCCEEDED, repeated.start().state());
        assertFalse(workspace.hasRecordedClassifiableStock(stocked, accepted));
        workspace.setContainerScan(ContainerScanResult.success(List.of(empty)));
        assertTrue(workspace.hasRecordedClassifiableStock(empty, accepted));
    }

    @Test
    void legacyCompositeExecutorsAreNotRegistered() {
        Set<String> ids = StandardTaskModuleFactories.create().stream()
                .map(factory -> factory.moduleId())
                .collect(java.util.stream.Collectors.toSet());

        assertFalse(ids.contains("AUTO_EAT_AT_COORDINATE"));
        assertFalse(ids.contains("SORT_CACHE_ITEMS"));
        assertFalse(ids.contains("SORT_CACHE_ITEMS_BY_CATEGORY_ZONE"));
        assertFalse(ids.contains("REFRESH_CATEGORY_MARKER_CACHE"));
        assertFalse(ids.contains("COLLECT_DROPPED_ITEMS_IN_REGION"));
    }


    @Test
    void manualMoveUsesExactPositionWithoutPersistingCoordinate() {
        RecordingMoveNavigation navigation = new RecordingMoveNavigation();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskExecutionContext context = context(
                navigation,
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        MoveToCoordinateModule module = new MoveToCoordinateModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.MOVE_TO_COORDINATE,
                        Map.of(
                                TaskModuleIds.X, "-12",
                                TaskModuleIds.Y, "64",
                                TaskModuleIds.Z, "30"
                        )
                )
        );

        assertEquals(TaskModuleState.RUNNING, module.start().state());
        assertEquals(
                List.of(new BlockPosition(-12, 64, 30)),
                navigation.exactPositions()
        );
        assertTrue(navigation.coordinatePositions().isEmpty());
        assertTrue(navigation.containerPositions().isEmpty());
        assertTrue(navigation.dimensionChecks().isEmpty());
        assertTrue(context.coordinates().isEmpty());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertEquals(
                List.of(new BlockPosition(-12, 64, 30)),
                navigation.statusPositions()
        );
        assertEquals(List.of(0), navigation.statusRadii());
    }

    @Test
    void manualMoveCancellationStopsNavigation() {
        RecordingMoveNavigation navigation = new RecordingMoveNavigation();
        MoveToCoordinateModule module = new MoveToCoordinateModule(
                context(
                        navigation,
                        new RecordingTransfers(),
                        new FailedWithdrawals(),
                        new FoodPort(),
                        new TaskRunWorkspace()
                ),
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.MOVE_TO_COORDINATE,
                        Map.of(
                                TaskModuleIds.X, "1",
                                TaskModuleIds.Y, "2",
                                TaskModuleIds.Z, "3"
                        )
                )
        );

        assertEquals(TaskModuleState.RUNNING, module.start().state());
        module.requestStop();

        assertEquals(1, navigation.cancellations());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
    }

    @Test
    void savedCoordinateWithoutRadiusUsesExactStartAndChecksDimension() {
        UUID coordinateId = UUID.randomUUID();
        CoordinatePoint point = new CoordinatePoint(
                coordinateId,
                "Exact",
                5,
                70,
                -9,
                "minecraft:overworld",
                null
        );
        RecordingMoveNavigation navigation = new RecordingMoveNavigation();
        MoveToCoordinateModule module = new MoveToCoordinateModule(
                context(
                        navigation,
                        new RecordingTransfers(),
                        new FailedWithdrawals(),
                        new FoodPort(),
                        new TaskRunWorkspace(),
                        Map.of(coordinateId, point)
                ),
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.MOVE_TO_COORDINATE,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "coordinate:" + coordinateId
                        )
                )
        );

        assertEquals(TaskModuleState.RUNNING, module.start().state());
        assertEquals(
                List.of("minecraft:overworld"),
                navigation.dimensionChecks()
        );
        assertEquals(
                List.of(new BlockPosition(5, 70, -9)),
                navigation.exactPositions()
        );
        assertTrue(navigation.coordinatePositions().isEmpty());
        assertTrue(navigation.containerPositions().isEmpty());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertEquals(
                List.of(new BlockPosition(5, 70, -9)),
                navigation.statusPositions()
        );
        assertEquals(List.of(0), navigation.statusRadii());
    }

    @Test
    void savedCoordinateRadiiUseExactNavigationEntry() {
        for (Integer radius : java.util.Arrays.asList(null, 0, 1, 2, 7)) {
            UUID coordinateId = UUID.randomUUID();
            CoordinatePoint point = new CoordinatePoint(
                    coordinateId,
                    "Nearby",
                    5,
                    70,
                    -9,
                    "minecraft:overworld",
                    radius
            );
            RecordingMoveNavigation navigation = new RecordingMoveNavigation();
            MoveToCoordinateModule module = new MoveToCoordinateModule(
                    context(
                            navigation,
                            new RecordingTransfers(),
                            new FailedWithdrawals(),
                            new FoodPort(),
                            new TaskRunWorkspace(),
                            Map.of(coordinateId, point)
                    ),
                    TaskStep.action(
                            UUID.randomUUID(),
                            TaskModuleIds.MOVE_TO_COORDINATE,
                            Map.of(
                                    TaskModuleIds.TARGET_REF,
                                    "coordinate:" + coordinateId
                            )
                    )
            );

            assertEquals(TaskModuleState.RUNNING, module.start().state());
            assertEquals(
                    List.of("minecraft:overworld"),
                    navigation.dimensionChecks()
            );
            assertEquals(
                    List.of(new BlockPosition(5, 70, -9)),
                    navigation.exactPositions()
            );
            assertTrue(navigation.coordinatePositions().isEmpty());
            assertTrue(navigation.containerPositions().isEmpty());
            assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
            assertEquals(
                    List.of(new BlockPosition(5, 70, -9)),
                    navigation.statusPositions()
            );
            assertEquals(List.of(0), navigation.statusRadii());
        }
    }

    @Test
    void outputMoveUsesContainerInteractionGoal() {
        ContainerTarget container = target(8, 60, -4, "container");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setContainerScan(ContainerScanResult.success(List.of(container)));
        workspace.selectNextContainer().orElseThrow();
        RecordingMoveNavigation navigation = new RecordingMoveNavigation();
        MoveToCoordinateModule module = new MoveToCoordinateModule(
                context(
                        navigation,
                        new RecordingTransfers(),
                        new FailedWithdrawals(),
                        new FoodPort(),
                        workspace
                ),
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.MOVE_TO_COORDINATE,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "output:container"
                        )
                )
        );

        assertEquals(TaskModuleState.RUNNING, module.start().state());
        assertEquals(
                List.of(new BlockPosition(8, 60, -4)),
                navigation.containerPositions()
        );
        assertTrue(navigation.exactPositions().isEmpty());
        assertTrue(navigation.coordinatePositions().isEmpty());
        assertTrue(navigation.dimensionChecks().isEmpty());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertEquals(
                List.of(new BlockPosition(8, 60, -4)),
                navigation.statusPositions()
        );
        assertNull(navigation.statusRadii().getFirst());
    }

    @Test
    void containerMoveSucceedsImmediatelyInsideInteractionRange() {
        ContainerTarget container = target(8, 60, -4, "container");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setContainerScan(ContainerScanResult.success(List.of(container)));
        workspace.selectNextContainer().orElseThrow();
        RecordingMoveNavigation navigation = new RecordingMoveNavigation();
        navigation.interactionTarget = container.position();
        MoveToCoordinateModule module = new MoveToCoordinateModule(
                context(
                        navigation,
                        new RecordingTransfers(),
                        new FailedWithdrawals(),
                        new FoodPort(),
                        workspace
                ),
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.MOVE_TO_COORDINATE,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "output:container"
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertTrue(navigation.containerPositions().isEmpty());
        assertEquals(1, navigation.interactionChecks());
    }

    @Test
    void unreachableClassificationStorageSkipsDepositAndTriesTheNextMatchingBox() {
        String item = "minecraft:iron_ingot";
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget blocked = target(5, 64, 5, "storage");
        ContainerTarget reachable = target(6, 64, 5, "storage");
        ContainerTarget wrongCategory = target(7, 64, 5, "storage");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(List.of(wrongCategory, blocked, reachable),
                Map.of(blocked, Set.of(item), reachable, Set.of(item),
                        wrongCategory, Set.of("minecraft:gold_ingot")), List.of(cache));
        workspace.selectNextContainer().orElseThrow();
        workspace.recordWithdrawn(item, 2);
        assertEquals(blocked, workspace.selectStorageTarget("carried", true).orElseThrow());

        RecordingTransfers transfers = new RecordingTransfers();
        TaskExecutionContext context = context(new NavigationPort() {
            @Override
            public NavigationStatus start(BlockPosition position) {
                return NavigationStatus.FAILED;
            }

            @Override
            public NavigationStatus status(BlockPosition position) {
                return NavigationStatus.FAILED;
            }

            @Override
            public void cancel() {
            }
        }, transfers, new FailedWithdrawals(), new FoodPort(), workspace);
        MoveToCoordinateModule move = new MoveToCoordinateModule(context, TaskStep.action(
                UUID.randomUUID(), TaskModuleIds.MOVE_TO_COORDINATE,
                Map.of(TaskModuleIds.TARGET_REF, "output:storage")));
        DepositItemsActionModule deposit = new DepositItemsActionModule(context, TaskStep.action(
                UUID.randomUUID(), TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(TaskModuleIds.TARGET_REF, "output:storage",
                        TaskModuleIds.ITEM_SOURCE, "carried", TaskModuleIds.RETRY_ON_FAILURE,
                        "true")));

        assertEquals(TaskModuleState.SUCCEEDED, move.start().state());
        assertEquals(TaskModuleState.SUCCEEDED, deposit.start().state());
        assertEquals(Map.of(item, 2), workspace.carriedItems());
        assertEquals(reachable, workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void savedCoordinateRadiusSevenDoesNotArriveTwoBlocksAway() {
        UUID coordinateId = UUID.randomUUID();
        CoordinatePoint point = new CoordinatePoint(
                coordinateId,
                "Saved",
                5,
                70,
                -9,
                "minecraft:overworld",
                7
        );
        ToggleNavigationService service = new ToggleNavigationService();
        service.navigating = true;
        AtomicReference<BlockPosition> player = new AtomicReference<>(
                new BlockPosition(7, 70, -9)
        );
        MoveToCoordinateModule module = savedCoordinateModule(
                context(
                        new BaritoneAutomationNavigation(service, worldAt(player)),
                        new RecordingTransfers(),
                        new FailedWithdrawals(),
                        new FoodPort(),
                        new TaskRunWorkspace(),
                        Map.of(coordinateId, point)
                ),
                coordinateId
        );

        assertEquals(TaskModuleState.RUNNING, module.start().state());
        assertEquals(TaskModuleState.RUNNING, module.tick().state());
        assertEquals(new BlockPosition(5, 70, -9), service.exactTarget);
        assertEquals(0, service.cancellations);

        player.set(new BlockPosition(5, 70, -9));
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertEquals(1, service.cancellations);
    }

    @Test
    void savedCoordinateRadiusSevenFailsWhenPathStopsAwayFromTarget() {
        UUID coordinateId = UUID.randomUUID();
        CoordinatePoint point = new CoordinatePoint(
                coordinateId,
                "Saved",
                5,
                70,
                -9,
                "minecraft:overworld",
                7
        );
        ToggleNavigationService service = new ToggleNavigationService();
        service.navigating = true;
        AtomicReference<BlockPosition> player = new AtomicReference<>(
                new BlockPosition(7, 70, -9)
        );
        MoveToCoordinateModule module = savedCoordinateModule(
                context(
                        new BaritoneAutomationNavigation(service, worldAt(player)),
                        new RecordingTransfers(),
                        new FailedWithdrawals(),
                        new FoodPort(),
                        new TaskRunWorkspace(),
                        Map.of(coordinateId, point)
                ),
                coordinateId
        );

        assertEquals(TaskModuleState.RUNNING, module.start().state());
        assertEquals(TaskModuleState.RUNNING, module.tick().state());
        service.navigating = false;
        assertEquals(TaskModuleState.FAILED, module.tick().state());
        assertEquals(1, service.cancellations);
    }

    @Test
    void markerSaveRequiresSameZoneAndEveryTargetRead() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        CategoryZoneDefinition zoneA = zone("A");
        CategoryZoneDefinition zoneB = zone("B");
        ContainerTarget markerA = target(1, 64, 1, "marker");
        ContainerTarget markerB = target(2, 64, 2, "marker");
        workspace.setMarkerPlan(
                zoneA.id(),
                new MarkerContainerScanAction.Plan(
                        Map.of(),
                        List.of(
                                new MarkerContainerScanAction.Target(markerA),
                                new MarkerContainerScanAction.Target(markerB)
                        ),
                        ""
                )
        );
        RecordingMarkerCache cache = new RecordingMarkerCache();
        TaskExecutionContext context = contextWithMarker(
                workspace,
                Map.of(zoneA.id(), zoneA, zoneB.id(), zoneB),
                cache
        );

        SaveCategoryMarkerCacheModule wrongZone = new SaveCategoryMarkerCacheModule(
                context,
                markerSaveStep(zoneB)
        );
        assertEquals(TaskModuleState.FAILED, wrongZone.start().state());

        SaveCategoryMarkerCacheModule incomplete =
                new SaveCategoryMarkerCacheModule(
                        context,
                        markerSaveStep(zoneA)
                );
        assertEquals(TaskModuleState.FAILED, incomplete.start().state());

        workspace.recordContainerRead(markerA, Set.of("minecraft:iron_ingot"));
        assertEquals(TaskModuleState.FAILED, incomplete.start().state());

        workspace.recordContainerRead(markerB, Set.of("minecraft:gold_ingot"));
        assertEquals(TaskModuleState.SUCCEEDED, incomplete.start().state());
        assertTrue(cache.saved);
    }

    @Test
    void outputFailuresNameReferenceAndPrerequisiteModule() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();

        assertMissingOutput(
                workspace,
                "container",
                "容器",
                "选择容器",
                TaskModuleIds.SELECT_CONTAINER
        );
        assertMissingOutput(
                workspace,
                "storage",
                "存储位",
                "选择存储位",
                TaskModuleIds.SELECT_STORAGE_TARGET
        );
        assertMissingOutput(
                workspace,
                "marker",
                "标记容器",
                "选择标记容器",
                TaskModuleIds.SELECT_MARKER_CONTAINER
        );
    }

    @Test
    void readContainerUsesSharedMarkerRequirementAndReadsSelectedMarker() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskExecutionContext missingContext = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        IllegalStateException shared = assertThrows(
                IllegalStateException.class,
                () -> ResourceReferenceSupport.markerContainer(workspace)
        );
        IllegalStateException module = assertThrows(
                IllegalStateException.class,
                () -> new ReadContainerModule(
                        missingContext,
                        readContainerStep()
                )
        );
        assertEquals(shared.getMessage(), module.getMessage());

        ContainerTarget marker = target(3, 64, 3, "marker");
        workspace.setMarkerPlan(
                UUID.randomUUID(),
                new MarkerContainerScanAction.Plan(
                        Map.of(),
                        List.of(new MarkerContainerScanAction.Target(marker)),
                        ""
                )
        );
        workspace.selectMarker();
        RecordingInspection inspection = new RecordingInspection(marker);
        TaskExecutionContext selectedContext = contextWithInspection(
                workspace,
                inspection
        );

        ReadContainerModule reader = new ReadContainerModule(
                selectedContext,
                readContainerStep()
        );
        TaskModuleResult result = reader.start();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(marker, inspection.observedTarget());
        assertEquals(1, workspace.markerReadCount());
    }

    @Test
    void readContainerCoordinateDoesNotRequireAMarkerPlan() {
        UUID coordinateId = UUID.randomUUID();
        BlockPosition expectedPosition = new BlockPosition(4, 64, 4);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        RecordingInspection inspection = new RecordingInspection(
                new ContainerTarget(expectedPosition, "container")
        );
        TaskExecutionContext context = contextWithInspection(
                workspace,
                inspection
        );
        TaskExecutionContext coordinateContext = new TaskExecutionContext(
                context.navigation(),
                context.droppedItems(),
                context.containerRegions(),
                context.containerTransfers(),
                context.containerWithdrawals(),
                context.containerInspections(),
                context.categoryMarkerCache(),
                context.foodConsumption(),
                context.categoryZonesPort(),
                Map.of(
                        coordinateId,
                        new CoordinatePoint(
                                coordinateId,
                                "Saved chest",
                                4,
                                64,
                                4
                        )
                ),
                context.regions(),
                context.categoryZones(),
                workspace
        );
        ReadContainerModule module = new ReadContainerModule(
                coordinateContext,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.READ_CONTAINER,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "coordinate:" + coordinateId
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertEquals(expectedPosition, inspection.observedTarget().position());
        assertEquals(
                "minecraft:iron_ingot",
                workspace.lastContainerRead()
        );
    }

    @Test
    void selectedMarkerDoesNotRestrictCoordinateReads() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget marker = target(1, 64, 1, "marker");
        workspace.setMarkerPlan(
                UUID.randomUUID(),
                new MarkerContainerScanAction.Plan(
                        Map.of(),
                        List.of(new MarkerContainerScanAction.Target(marker)),
                        ""
                )
        );
        workspace.selectMarker();
        UUID coordinateId = UUID.randomUUID();
        ContainerTarget expected = target(9, 64, 9, "container");
        RecordingInspection inspection = new RecordingInspection(expected);
        TaskExecutionContext base = contextWithInspection(workspace, inspection);
        TaskExecutionContext context = new TaskExecutionContext(
                base.navigation(),
                base.droppedItems(),
                base.containerRegions(),
                base.containerTransfers(),
                base.containerWithdrawals(),
                base.containerInspections(),
                base.categoryMarkerCache(),
                base.foodConsumption(),
                base.categoryZonesPort(),
                Map.of(
                        coordinateId,
                        new CoordinatePoint(
                                coordinateId,
                                "Direct",
                                9,
                                64,
                                9
                        )
                ),
                base.regions(),
                base.categoryZones(),
                workspace
        );
        ReadContainerModule module = new ReadContainerModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.READ_CONTAINER,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "coordinate:" + coordinateId
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertEquals(expected, inspection.observedTarget());
        assertEquals(0, workspace.markerReadCount());
    }

    @Test
    void explicitAllMarkerSourcesAllowBindingsWithoutMarkerContainers() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        CategoryZoneDefinition zone = new CategoryZoneDefinition(
                UUID.randomUUID(),
                "Display markers",
                List.of(
                        new CategoryBindingDefinition(
                                UUID.randomUUID(),
                                new AreaBounds(0, 64, 0, 3, 66, 3),
                                new AreaBounds(5, 64, 0, 8, 66, 3),
                                "test-rule"
                        ),
                        new CategoryBindingDefinition(
                                UUID.randomUUID(),
                                new AreaBounds(12, 64, 0, 15, 66, 3),
                                new AreaBounds(17, 64, 0, 20, 66, 3),
                                "test-rule"
                        )
                )
        );
        TaskExecutionContext base = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        TaskExecutionContext context = new TaskExecutionContext(
                base.navigation(),
                base.droppedItems(),
                (region, types, volume) ->
                        ContainerScanResult.success(List.of()),
                base.containerTransfers(),
                base.containerWithdrawals(),
                base.containerInspections(),
                base.categoryMarkerCache(),
                base.foodConsumption(),
                base.categoryZonesPort(),
                base.coordinates(),
                base.regions(),
                base.categoryZones(),
                workspace
        );

        MarkerContainerScanAction.Plan plan = MarkerContainerScanAction.scan(
                context,
                zone,
                100,
                Set.copyOf(EnumSet.allOf(CategoryMarkerSource.class))
        );

        assertEquals("", plan.error());
        assertTrue(plan.targets().isEmpty());
        assertEquals(2, plan.cache().size());
        assertTrue(plan.cache().values().stream().allMatch(Map::isEmpty));
    }

    @Test
    void explicitSourcesKeepEmptyCacheForBindingWithoutMarkerContainer() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        CategoryBindingDefinition withContainer = new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(0, 64, 0, 3, 66, 3),
                new AreaBounds(5, 64, 0, 8, 66, 3),
                "test-rule"
        );
        CategoryBindingDefinition withoutContainer =
                new CategoryBindingDefinition(
                        UUID.randomUUID(),
                        new AreaBounds(12, 64, 0, 15, 66, 3),
                        new AreaBounds(17, 64, 0, 20, 66, 3),
                        "test-rule"
                );
        CategoryZoneDefinition zone = new CategoryZoneDefinition(
                UUID.randomUUID(),
                "Mixed markers",
                List.of(withContainer, withoutContainer)
        );
        TaskExecutionContext base = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        TaskExecutionContext context = new TaskExecutionContext(
                base.navigation(),
                base.droppedItems(),
                (region, types, volume) -> region.id().equals(
                        withContainer.id()
                )
                        ? ContainerScanResult.success(List.of(
                                target(1, 64, 1, "marker")
                        ))
                        : ContainerScanResult.success(List.of()),
                base.containerTransfers(),
                base.containerWithdrawals(),
                base.containerInspections(),
                base.categoryMarkerCache(),
                base.foodConsumption(),
                base.categoryZonesPort(),
                base.coordinates(),
                base.regions(),
                base.categoryZones(),
                workspace
        );

        MarkerContainerScanAction.Plan plan = MarkerContainerScanAction.scan(
                context,
                zone,
                100,
                Set.copyOf(EnumSet.allOf(CategoryMarkerSource.class))
        );

        assertEquals("", plan.error());
        assertEquals(1, plan.targets().size());
        assertEquals(2, plan.cache().size());
        assertTrue(plan.cache().get(withoutContainer.id()).isEmpty());
        assertTrue(plan.cache().get(withContainer.id()).isEmpty());
    }

    @Test
    void cacheScanRemainsTheContainerSourceWhenCategoriesTargetOtherContainers() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget storage = target(5, 64, 5, "storage");
        workspace.setContainerScan(
                UUID.randomUUID(),
                ContainerScanResult.success(List.of(cache))
        );
        workspace.setResolvedCategories(
                List.of(storage),
                Map.of(storage, Set.of("minecraft:iron_ingot")),
                List.of(cache)
        );

        assertEquals(cache, workspace.selectNextContainer().orElseThrow());
        assertEquals(
                Set.of("minecraft:iron_ingot"),
                workspace.selectedContainerItemIds()
        );
        assertFalse(workspace.hasContainers());
    }

    @Test
    void storageSelectionUsesTheConfiguredSourceLedger() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget storage = target(5, 64, 5, "storage");
        workspace.setResolvedCategories(
                List.of(storage),
                Map.of(storage, Set.of("minecraft:gold_ingot")),
                List.of()
        );
        workspace.recordCollected("minecraft:gold_ingot", 2);

        assertEquals(
                storage,
                workspace.selectStorageTarget("collected").orElseThrow()
        );
        assertTrue(workspace.selectStorageTarget("carried").isEmpty());
    }

    @Test
    void fullStorageTargetIsSkippedWhenSwitchingSameCategory() {
        ContainerTarget first = target(5, 64, 5, "storage-a");
        ContainerTarget second = target(7, 64, 7, "storage-b");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(
                List.of(first, second),
                Map.of(
                        first, Set.of("minecraft:iron_ingot"),
                        second, Set.of("minecraft:iron_ingot")
                ),
                List.of()
        );
        workspace.recordWithdrawn("minecraft:iron_ingot", 2);
        workspace.selectStorageTarget("carried").orElseThrow();
        workspace.markTargetUnavailable(first);
        workspace.resetStorageSelection();

        assertEquals(second, workspace.selectStorageTarget("carried")
                .orElseThrow());
    }

    @Test
    void storageSelectionSkipsTheCurrentCacheContainerPosition() {
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget samePosition = target(1, 64, 1, "storage");
        ContainerTarget other = target(5, 64, 5, "storage-other");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(
                List.of(samePosition, other),
                Map.of(
                        samePosition, Set.of("minecraft:iron_ingot"),
                        other, Set.of("minecraft:iron_ingot")
                ),
                List.of(cache)
        );
        workspace.recordWithdrawn("minecraft:iron_ingot", 1);
        workspace.selectNextContainer().orElseThrow();

        assertEquals(other, workspace.selectStorageTarget("carried")
                .orElseThrow());
    }

    @Test
    void storageSelectionFallsBackToTheCurrentCacheContainer() {
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget storage = target(5, 64, 5, "storage");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(
                List.of(storage),
                Map.of(storage, Set.of("minecraft:gold_ingot")),
                List.of(cache)
        );
        workspace.selectNextContainer().orElseThrow();
        workspace.recordWithdrawn("minecraft:iron_ingot", 2);

        assertEquals(
                cache,
                workspace.selectStorageTarget("carried", true).orElseThrow()
        );
        assertTrue(workspace.selectedStorageUsesCacheFallback());
        workspace.markTargetUnavailable(cache);
        assertTrue(workspace
                .selectStorageTarget("carried", true)
                .isEmpty());
    }

    @Test
    void unmatchedCacheItemsAreNotSelectedForWithdrawal() {
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget storage = target(5, 64, 5, "storage");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(
                List.of(storage),
                Map.of(storage, Set.of("minecraft:iron_ingot")),
                List.of(cache)
        );

        assertEquals(
                Set.of("minecraft:iron_ingot"),
                workspace.withdrawItemIdsFor(cache)
        );
    }

    @Test
    void retryDepositMarksFullTargetAndKeepsRemainingItemsInLedger() {
        String itemId = "minecraft:iron_ingot";
        ContainerTarget first = target(5, 64, 5, "storage-a");
        ContainerTarget second = target(7, 64, 7, "storage-b");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(
                List.of(first, second),
                Map.of(first, Set.of(itemId), second, Set.of(itemId)),
                List.of()
        );
        workspace.recordWithdrawn(itemId, 2);
        workspace.selectStorageTarget("carried").orElseThrow();
        TaskExecutionContext context = contextWithTransfers(
                context(
                        new ForbiddenNavigation(),
                        new RecordingTransfers(),
                        new FailedWithdrawals(),
                        new FoodPort(),
                        workspace
                ),
                new FixedTransfers(ContainerTransferResult.failed(
                        "full",
                        Map.of(itemId, 2)
                ))
        );
        DepositItemsActionModule module = new DepositItemsActionModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        Map.of(
                                TaskModuleIds.TARGET_REF, "output:storage",
                                TaskModuleIds.ITEM_SOURCE, "carried",
                                TaskModuleIds.RETRY_ON_FAILURE, "true"
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertEquals(Map.of(itemId, 2), workspace.carriedItems());
        workspace.resetStorageSelection();
        assertEquals(second, workspace.selectStorageTarget("carried")
                .orElseThrow());
    }

    @Test
    void categoryDepositWithMixedLedgersOnlyConsumesSelectedSource() {
        String collectedItem = "minecraft:gold_ingot";
        String carriedItem = "minecraft:iron_ingot";
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.recordCollected(collectedItem, 2);
        workspace.recordWithdrawn(carriedItem, 2);
        ContainerTarget storage = target(5, 64, 5, "storage");
        workspace.setResolvedCategories(
                List.of(storage),
                Map.of(storage, Set.of(collectedItem)),
                List.of()
        );
        workspace.selectStorageTarget("collected").orElseThrow();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        TaskExecutionContext transferContext = contextWithTransfers(
                context,
                new FixedTransfers(ContainerTransferResult.succeeded(
                        "deposited",
                        Map.of()
                ))
        );
        DepositItemsActionModule module = new DepositItemsActionModule(
                transferContext,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "output:storage",
                                TaskModuleIds.ITEM_SOURCE,
                                "collected"
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertTrue(workspace.collectedItems().isEmpty());
        assertEquals(Map.of(carriedItem, 2), workspace.carriedItems());
        assertEquals(Map.of(carriedItem, 2), workspace.managedItems());
    }

    @Test
    void managedDepositAllocatesActualDepositedItemsBetweenLedgers() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.recordCollected("minecraft:iron_ingot", 3);
        workspace.recordWithdrawn("minecraft:iron_ingot", 2);

        workspace.consumeDeposit(
                "managed",
                Map.of("minecraft:iron_ingot", 5),
                Map.of("minecraft:iron_ingot", 2)
        );

        assertEquals(2, workspace.managedItemCount());
        assertEquals(Map.of("minecraft:iron_ingot", 2),
                workspace.collectedItems());
        assertTrue(workspace.carriedItems().isEmpty());
    }

    @Test
    void depositOnlyConsumesTheSelectedThisRunLedger() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.recordCollected("minecraft:iron_ingot", 5);
        workspace.recordWithdrawn("minecraft:diamond_pickaxe", 1);
        workspace.recordWithdrawn("minecraft:bread", 3);

        workspace.consumeDeposit(
                "collected",
                Map.of("minecraft:iron_ingot", 5),
                Map.of()
        );

        assertTrue(workspace.collectedItems().isEmpty());
        assertEquals(
                Map.of("minecraft:diamond_pickaxe", 1, "minecraft:bread", 3),
                workspace.carriedItems()
        );
        assertEquals(
                Map.of("minecraft:diamond_pickaxe", 1, "minecraft:bread", 3),
                workspace.managedItems()
        );
    }

    @Test
    void collectedCacheDepositPublishesCacheUpdate() {
        UUID regionId = UUID.randomUUID();
        String itemId = "minecraft:iron_ingot";
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setContainerScan(
                regionId,
                ContainerScanResult.success(List.of(target(1, 64, 1, "cache")))
        );
        workspace.recordCollected(itemId, 2);
        workspace.selectNextContainer().orElseThrow();
        TaskExecutionContext context = contextWithTransfers(
                context(
                        new ForbiddenNavigation(),
                        new RecordingTransfers(),
                        new FailedWithdrawals(),
                        new FoodPort(),
                        workspace
                ),
                new FixedTransfers(ContainerTransferResult.succeeded(
                        "stored",
                        Map.of()
                ))
        );
        DepositItemsActionModule module = new DepositItemsActionModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        Map.of(
                                TaskModuleIds.TARGET_REF, "output:container",
                                TaskModuleIds.ITEM_SOURCE, "collected"
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertEquals(Map.of(regionId, 2), workspace.consumeCacheUpdates());
    }

    @Test
    void managedCleanupReturnDoesNotPublishCacheUpdate() {
        UUID regionId = UUID.randomUUID();
        String itemId = "minecraft:iron_ingot";
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setContainerScan(
                regionId,
                ContainerScanResult.success(List.of(target(1, 64, 1, "cache")))
        );
        workspace.recordManagedAcquired(itemId, 2);
        workspace.selectNextContainer().orElseThrow();
        TaskExecutionContext context = contextWithTransfers(
                context(
                        new ForbiddenNavigation(),
                        new RecordingTransfers(),
                        new FailedWithdrawals(),
                        new FoodPort(),
                        workspace
                ),
                new FixedTransfers(ContainerTransferResult.succeeded(
                        "returned",
                        Map.of()
                ))
        );
        DepositItemsActionModule module = new DepositItemsActionModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        Map.of(
                                TaskModuleIds.TARGET_REF, "output:container",
                                TaskModuleIds.ITEM_SOURCE, "managed",
                                TaskModuleIds.RETRY_ON_FAILURE, "true"
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertTrue(workspace.consumeCacheUpdates().isEmpty());
    }

    @Test
    void assertModuleDefaultsToManagedAndUsesTheRequestedLedger() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        TaskStep defaultStep = TaskStep.action(
                UUID.randomUUID(),
                TaskModuleIds.ASSERT_NO_MANAGED_ITEMS,
                Map.of()
        );
        TaskStep collectedStep = TaskStep.action(
                UUID.randomUUID(),
                TaskModuleIds.ASSERT_NO_MANAGED_ITEMS,
                Map.of(TaskModuleIds.ITEM_SOURCE, "collected")
        );

        assertEquals(
                TaskModuleState.SUCCEEDED,
                new AssertNoManagedItemsModule(context, defaultStep)
                        .start()
                        .state()
        );
        workspace.recordManagedAcquired("minecraft:iron_ingot", 1);
        TaskModuleResult managedFailure = new AssertNoManagedItemsModule(
                context,
                defaultStep
        ).start();
        assertEquals(TaskModuleState.FAILED, managedFailure.state());
        assertEquals(
                "任务结束仍有受管物品: {minecraft:iron_ingot=1}",
                managedFailure.message()
        );
        assertEquals(
                TaskModuleState.SUCCEEDED,
                new AssertNoManagedItemsModule(context, collectedStep)
                        .start()
                        .state()
        );

        workspace.consumeManaged("minecraft:iron_ingot", 1);
        workspace.recordCollected("minecraft:gold_ingot", 2);
        TaskModuleResult collectedFailure = new AssertNoManagedItemsModule(
                context,
                collectedStep
        ).start();
        assertEquals(TaskModuleState.FAILED, collectedFailure.state());
        assertTrue(collectedFailure.message().contains("已收集物品"));

        TaskRunWorkspace carriedWorkspace = new TaskRunWorkspace();
        carriedWorkspace.recordWithdrawn("minecraft:diamond", 1);
        TaskExecutionContext carriedContext = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                carriedWorkspace
        );
        assertEquals(
                TaskModuleState.SUCCEEDED,
                new AssertNoManagedItemsModule(carriedContext, collectedStep)
                        .start()
                        .state()
        );
        TaskModuleResult carriedFailure = new AssertNoManagedItemsModule(
                carriedContext,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.ASSERT_NO_MANAGED_ITEMS,
                        Map.of(TaskModuleIds.ITEM_SOURCE, "carried")
                )
        ).start();
        assertEquals(TaskModuleState.FAILED, carriedFailure.state());
        assertTrue(carriedFailure.message().contains("已取出物品"));
    }

    @Test
    void capacityLimitedDepositKeepsLedgerForTheNextContainer() {
        String itemId = "minecraft:iron_ingot";
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.recordManagedAcquired(itemId, 5);
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace,
                Map.of(
                        firstId,
                        new CoordinatePoint(firstId, "Full", 1, 64, 1),
                        secondId,
                        new CoordinatePoint(secondId, "Next", 2, 64, 1)
                )
        );
        ContainerTransferResult capacityLimited = ContainerTransferResult
                .succeeded("容器容量不足", Map.of(itemId, 5));
        DepositItemsActionModule firstAttempt = depositModule(
                context,
                firstId,
                capacityLimited
        );

        TaskModuleResult firstResult = firstAttempt.start();

        assertEquals(TaskModuleState.SUCCEEDED, firstResult.state());
        assertEquals(Map.of(itemId, 5), workspace.managedItems());
        DepositItemsActionModule secondAttempt = depositModule(
                context,
                secondId,
                ContainerTransferResult.succeeded("deposited", Map.of())
        );
        assertEquals(TaskModuleState.SUCCEEDED, secondAttempt.start().state());
        assertTrue(workspace.managedItems().isEmpty());
    }

    @Test
    void unifiedDepositAssertionFailsWhenCapacityLeavesCollectedItems() {
        String itemId = "minecraft:iron_ingot";
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.recordCollected(itemId, 5);
        UUID coordinateId = UUID.randomUUID();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace,
                Map.of(
                        coordinateId,
                        new CoordinatePoint(
                                coordinateId,
                                "Full",
                                1,
                                64,
                                1
                        )
                )
        );
        FixedTransfers transfers = new FixedTransfers(
                ContainerTransferResult.succeeded(
                        "容器容量不足",
                        Map.of(itemId, 5)
                )
        );
        TaskExecutionContext transferContext = contextWithTransfers(
                context,
                transfers
        );
        DepositItemsActionModule deposit = new DepositItemsActionModule(
                transferContext,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "coordinate:" + coordinateId,
                                TaskModuleIds.ITEM_SOURCE,
                                "collected"
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, deposit.start().state());
        assertEquals(Map.of(itemId, 5), workspace.collectedItems());
        assertEquals(Map.of(itemId, 5), workspace.managedItems());
        TaskModuleResult assertion = new AssertNoManagedItemsModule(
                transferContext,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.ASSERT_NO_MANAGED_ITEMS,
                        Map.of(TaskModuleIds.ITEM_SOURCE, "collected")
                )
        ).start();
        assertEquals(TaskModuleState.FAILED, assertion.state());
    }

    @Test
    void failedTransferStillFailsWithoutConsumingTheLedger() {
        String itemId = "minecraft:iron_ingot";
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.recordManagedAcquired(itemId, 5);
        UUID coordinateId = UUID.randomUUID();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace,
                Map.of(
                        coordinateId,
                        new CoordinatePoint(
                                coordinateId,
                                "Failure",
                                1,
                                64,
                                1
                        )
                )
        );
        DepositItemsActionModule module = depositModule(
                context,
                coordinateId,
                ContainerTransferResult.failed(
                        "服务器拒绝存入",
                        Map.of(itemId, 5)
                )
        );

        TaskModuleResult result = module.start();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertEquals("服务器拒绝存入", result.message());
        assertEquals(Map.of(itemId, 5), workspace.managedItems());
    }

    @Test
    void storageDepositFiltersCarriedItemsToTheSelectedCategory() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget storageA = target(5, 64, 5, "storage-a");
        ContainerTarget storageB = target(7, 64, 7, "storage-b");
        workspace.setContainerScan(
                UUID.randomUUID(),
                ContainerScanResult.success(List.of(cache))
        );
        workspace.setResolvedCategories(
                List.of(storageA, storageB),
                Map.of(
                        storageA, Set.of("minecraft:iron_ingot"),
                        storageB, Set.of("minecraft:gold_ingot")
                ),
                List.of(cache)
        );
        workspace.recordWithdrawn("minecraft:iron_ingot", 2);
        workspace.recordWithdrawn("minecraft:gold_ingot", 3);
        workspace.selectStorageTarget();

        RecordingTransfers transfers = new RecordingTransfers();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                transfers,
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        DepositItemsActionModule module = new DepositItemsActionModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        Map.of(
                                TaskModuleIds.TARGET_REF, "output:storage",
                                TaskModuleIds.ITEM_SOURCE, "carried"
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertEquals(
                Map.of("minecraft:iron_ingot", 2),
                transfers.requested
        );
        assertEquals(
                Map.of("minecraft:gold_ingot", 3),
                workspace.carriedItems()
        );
    }

    @Test
    void cacheFallbackDepositKeepsTheWholeCarriedLedger() {
        String itemId = "minecraft:iron_ingot";
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget storage = target(5, 64, 5, "storage");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(
                List.of(storage),
                Map.of(storage, Set.of("minecraft:gold_ingot")),
                List.of(cache)
        );
        workspace.selectNextContainer().orElseThrow();
        workspace.recordWithdrawn(itemId, 2);
        workspace.selectStorageTarget("carried", true).orElseThrow();
        RecordingTransfers transfers = new RecordingTransfers();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                transfers,
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        DepositItemsActionModule module = new DepositItemsActionModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "output:storage",
                                TaskModuleIds.ITEM_SOURCE,
                                "carried"
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertEquals(Map.of(itemId, 2), transfers.requested);
        assertTrue(workspace.carriedItems().isEmpty());
        assertTrue(workspace.managedItems().isEmpty());
    }

    @Test
    void noMovementDepositSkipsAnUnusableCacheFallbackNextTime() {
        String itemId = "minecraft:iron_ingot";
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget storage = target(5, 64, 5, "storage");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(
                List.of(storage),
                Map.of(storage, Set.of("minecraft:gold_ingot")),
                List.of(cache)
        );
        workspace.selectNextContainer().orElseThrow();
        workspace.recordWithdrawn(itemId, 2);
        workspace.selectStorageTarget("carried", true).orElseThrow();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        TaskExecutionContext transferContext = contextWithTransfers(
                context,
                new FixedTransfers(ContainerTransferResult.succeeded(
                        "缓存容器容量不足",
                        Map.of(itemId, 2)
                ))
        );
        DepositItemsActionModule module = new DepositItemsActionModule(
                transferContext,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "output:storage",
                                TaskModuleIds.ITEM_SOURCE,
                                "carried",
                                TaskModuleIds.RETRY_ON_FAILURE,
                                "true"
                        )
                )
        );

        assertEquals(TaskModuleState.SUCCEEDED, module.start().state());
        assertTrue(workspace
                .selectStorageTarget("carried", true)
                .isEmpty());
    }

    @Test
    void cacheFallbackDefersOnlyReturnedCategoriesAndKeepsScanning() {
        String blocked = "minecraft:iron_ingot";
        String next = "minecraft:gold_ingot";
        String last = "minecraft:copper_ingot";
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget otherCache = target(2, 64, 1, "cache");
        ContainerTarget storage = target(5, 64, 5, "storage");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(List.of(storage),
                Map.of(storage, Set.of(blocked, next, last)), List.of(cache, otherCache));
        workspace.selectNextContainer().orElseThrow();
        workspace.recordWithdrawn(blocked, 2);
        workspace.selectStorageTarget("carried", true).orElseThrow();
        workspace.markTargetUnavailable(storage);
        assertEquals(cache, workspace.selectStorageTarget("carried", true).orElseThrow());

        CapturingWithdrawal withdrawal = new CapturingWithdrawal();
        TaskExecutionContext context = context(new ForbiddenNavigation(), new RecordingTransfers(),
                withdrawal, new FoodPort(), workspace);
        DepositItemsActionModule deposit = new DepositItemsActionModule(context, TaskStep.action(
                UUID.randomUUID(), TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(TaskModuleIds.TARGET_REF, "output:storage",
                        TaskModuleIds.ITEM_SOURCE, "carried", TaskModuleIds.RETRY_ON_FAILURE,
                        "true")));
        TaskStep withdraw = TaskStep.action(UUID.randomUUID(), TaskModuleIds.WITHDRAW_ITEMS,
                Map.of(TaskModuleIds.TARGET_REF, "output:container",
                        TaskModuleIds.ITEM_SOURCE, "classified"));

        assertEquals(TaskModuleState.SUCCEEDED, deposit.start().state());
        assertTrue(workspace.cacheHasMore());
        assertEquals(TaskModuleState.SUCCEEDED, new WithdrawItemsModule(context, withdraw)
                .start().state());
        assertEquals(Set.of(next, last), withdrawal.matchingItemIds());

        assertEquals(otherCache, workspace.selectNextContainer().orElseThrow());
        assertEquals(TaskModuleState.SUCCEEDED, new WithdrawItemsModule(context, withdraw)
                .start().state());
        assertEquals(Set.of(blocked, next, last), withdrawal.matchingItemIds());
    }

    @Test
    void withdrawalMatchingItemsAreBoundToTheRequestedTarget() {
        ContainerTarget selected = target(1, 64, 1, "selected");
        ContainerTarget requested = target(9, 64, 9, "requested");
        ContainerTarget unclassified = target(8, 64, 8, "unclassified");
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(
                List.of(selected, requested),
                Map.of(
                        selected, Set.of("minecraft:iron_ingot"),
                        requested, Set.of("minecraft:gold_ingot")
                ),
                List.of(selected)
        );
        workspace.selectNextContainer().orElseThrow();
        UUID requestedId = UUID.randomUUID();
        UUID unclassifiedId = UUID.randomUUID();
        CapturingWithdrawal withdrawal = new CapturingWithdrawal();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                withdrawal,
                new FoodPort(),
                workspace,
                Map.of(
                        requestedId,
                        new CoordinatePoint(
                                requestedId,
                                "Requested",
                                9,
                                64,
                                9
                        ),
                        unclassifiedId,
                        new CoordinatePoint(
                                unclassifiedId,
                                "Unclassified",
                                8,
                                64,
                                8
                        )
                )
        );

        WithdrawItemsModule requestedModule = new WithdrawItemsModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.WITHDRAW_ITEMS,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "coordinate:" + requestedId,
                                TaskModuleIds.ITEM_SOURCE,
                                "classified"
                        )
                )
        );
        assertEquals(
                TaskModuleState.SUCCEEDED,
                requestedModule.start().state()
        );
        assertEquals(
                Set.of("minecraft:gold_ingot"),
                withdrawal.matchingItemIds()
        );

        WithdrawItemsModule unclassifiedModule = new WithdrawItemsModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.WITHDRAW_ITEMS,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "coordinate:" + unclassifiedId,
                                TaskModuleIds.ITEM_SOURCE,
                                "classified"
                        )
                )
        );
        assertEquals(
                TaskModuleState.SUCCEEDED,
                unclassifiedModule.start().state()
        );
        assertTrue(withdrawal.matchingItemIds().isEmpty());
    }

    @Test
    void withdrawalStopDrainsActualPartialResultWithoutNavigation() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget cache = target(1, 64, 1, "cache");
        ContainerTarget storage = target(5, 64, 5, "storage");
        workspace.setContainerScan(
                UUID.randomUUID(),
                ContainerScanResult.success(List.of(cache))
        );
        workspace.setResolvedCategories(
                List.of(storage),
                Map.of(storage, Set.of("minecraft:iron_ingot")),
                List.of(cache)
        );
        workspace.selectNextContainer();
        PartialWithdrawal withdrawal = new PartialWithdrawal();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                withdrawal,
                new FoodPort(),
                workspace
        );
        WithdrawItemsModule module = new WithdrawItemsModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.WITHDRAW_ITEMS,
                        Map.of(
                                TaskModuleIds.TARGET_REF, "output:container",
                                TaskModuleIds.ITEM_SOURCE, "classified"
                        )
                )
        );

        module.start();
        module.requestStop();
        assertFalse(module.safeToStop());
        TaskModuleResult result = module.tick();

        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(module.safeToStop());
        assertEquals(2, workspace.carriedItems()
                .get("minecraft:iron_ingot"));
        assertEquals(2, workspace.managedItems()
                .get("minecraft:iron_ingot"));
    }


    @Test
    void acquiredFoodIsManagedAndConsumedWithoutTouchingOriginalInventory() {
        UUID coordinateId = UUID.randomUUID();
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        AcquiringFoodPort food = new AcquiringFoodPort();
        TaskExecutionContext context = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                food,
                workspace,
                Map.of(
                        coordinateId,
                        new CoordinatePoint(
                                coordinateId,
                                "Food",
                                1,
                                64,
                                1
                        )
                )
        );
        WithdrawFoodModule withdraw = new WithdrawFoodModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.WITHDRAW_FOOD,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "coordinate:" + coordinateId,
                                TaskModuleIds.FOOD_ITEM_ID,
                                "minecraft:bread"
                        )
                )
        );
        EatFoodModule eat = new EatFoodModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.EAT_FOOD,
                        Map.of(
                                TaskModuleIds.FOOD_ITEM_ID,
                                "minecraft:bread"
                        )
                )
        );

        assertEquals(TaskModuleState.WAITING, withdraw.start().state());
        assertEquals(TaskModuleState.SUCCEEDED, withdraw.tick().state());
        assertEquals(1, workspace.managedItems().get("minecraft:bread"));
        assertTrue(workspace.carriedItems().isEmpty());
        eat.start();
        eat.requestStop();
        assertFalse(eat.safeToStop());
        assertEquals(TaskModuleState.SUCCEEDED, eat.tick().state());
        assertTrue(eat.safeToStop());
        assertTrue(workspace.managedItems().isEmpty());
    }


    private static void assertMissingOutput(
            TaskRunWorkspace workspace,
            String key,
            String label,
            String selectionName,
            String prerequisiteModuleId
    ) {
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> ResourceReferenceSupport.output(
                        workspace,
                        ResourceReference.output(key)
                )
        );
        assertTrue(failure.getMessage().contains("output:" + key));
        assertTrue(failure.getMessage().contains(label));
        assertTrue(failure.getMessage().contains(selectionName));
        assertTrue(failure.getMessage().contains(prerequisiteModuleId));
    }

    private static TaskExecutionContext contextWithInspection(
            TaskRunWorkspace workspace,
            ContainerInspectionPort inspection
    ) {
        TaskExecutionContext base = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        return new TaskExecutionContext(
                base.navigation(),
                base.droppedItems(),
                base.containerRegions(),
                base.containerTransfers(),
                base.containerWithdrawals(),
                inspection,
                base.categoryMarkerCache(),
                base.foodConsumption(),
                base.categoryZonesPort(),
                base.coordinates(),
                base.regions(),
                base.categoryZones(),
                workspace
        );
    }

    private static TaskExecutionContext contextWithMarker(
            TaskRunWorkspace workspace,
            Map<UUID, CategoryZoneDefinition> zones,
            CategoryMarkerCachePort cache
    ) {
        TaskExecutionContext base = context(
                new ForbiddenNavigation(),
                new RecordingTransfers(),
                new FailedWithdrawals(),
                new FoodPort(),
                workspace
        );
        return new TaskExecutionContext(
                base.navigation(),
                base.droppedItems(),
                base.containerRegions(),
                base.containerTransfers(),
                base.containerWithdrawals(),
                base.containerInspections(),
                cache,
                base.foodConsumption(),
                base.categoryZonesPort(),
                base.coordinates(),
                base.regions(),
                zones,
                workspace
        );
    }

    private static CategoryZoneDefinition zone(String name) {
        return new CategoryZoneDefinition(
                UUID.randomUUID(),
                name,
                List.of(new CategoryBindingDefinition(
                        UUID.randomUUID(),
                        new AreaBounds(0, 0, 0, 1, 1, 1),
                        new AreaBounds(2, 0, 0, 3, 1, 1),
                        "test-rule"
                ))
        );
    }

    private static TaskStep readContainerStep() {
        return TaskStep.action(
                UUID.randomUUID(),
                TaskModuleIds.READ_CONTAINER,
                Map.of()
        );
    }

    private static TaskStep markerSaveStep(CategoryZoneDefinition zone) {
        return TaskStep.action(
                UUID.randomUUID(),
                TaskModuleIds.SAVE_CATEGORY_MARKER_CACHE,
                Map.of(
                        TaskModuleIds.CATEGORY_ZONE_REF,
                        "category-zone:" + zone.id()
                )
        );
    }

    private static DepositItemsActionModule depositModule(
            TaskExecutionContext context,
            UUID coordinateId,
            ContainerTransferResult result
    ) {
        FixedTransfers transfers = new FixedTransfers(result);
        TaskExecutionContext transferContext = contextWithTransfers(
                context,
                transfers
        );
        return new DepositItemsActionModule(
                transferContext,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.DEPOSIT_ITEMS_TO_CONTAINERS,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "coordinate:" + coordinateId,
                                TaskModuleIds.ITEM_SOURCE,
                                "managed"
                        )
                )
        );
    }

    private static TaskExecutionContext contextWithTransfers(
            TaskExecutionContext context,
            ContainerTransferPort transfers
    ) {
        return new TaskExecutionContext(
                context.navigation(),
                context.droppedItems(),
                context.containerRegions(),
                transfers,
                context.containerWithdrawals(),
                context.containerInspections(),
                context.categoryMarkerCache(),
                context.foodConsumption(),
                context.categoryZonesPort(),
                context.coordinates(),
                context.regions(),
                context.categoryZones(),
                context.workspace()
        );
    }

    private static TaskExecutionContext context(
            NavigationPort navigation,
            ContainerTransferPort transfers,
            ContainerWithdrawalPort withdrawals,
            FoodConsumptionPort food,
            TaskRunWorkspace workspace
    ) {
        return context(
                navigation,
                transfers,
                withdrawals,
                food,
                workspace,
                Map.of()
        );
    }

    private static TaskExecutionContext context(
            NavigationPort navigation,
            ContainerTransferPort transfers,
            ContainerWithdrawalPort withdrawals,
            FoodConsumptionPort food,
            TaskRunWorkspace workspace,
            Map<UUID, CoordinatePoint> coordinates
    ) {
        DroppedItemObserverPort dropped = new DroppedItemObserverPort() {
            @Override
            public List<DroppedItem> observe(BlockPosition center, int radius) {
                return List.of();
            }

            @Override
            public List<DroppedItem> observe(RegionDefinition region) {
                return List.of();
            }
        };
        ContainerRegionPort regions = (region, types, volume) ->
                ContainerScanResult.failure("unused");
        CategoryZonePort zones = (zone, types, volume) ->
                CategoryZoneResult.failure("unused");
        return new TaskExecutionContext(
                navigation,
                dropped,
                regions,
                transfers,
                withdrawals,
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                food,
                zones,
                coordinates,
                Map.of(),
                Map.of(),
                workspace
        );
    }

    private static MoveToCoordinateModule savedCoordinateModule(
            TaskExecutionContext context,
            UUID coordinateId
    ) {
        return new MoveToCoordinateModule(
                context,
                TaskStep.action(
                        UUID.randomUUID(),
                        TaskModuleIds.MOVE_TO_COORDINATE,
                        Map.of(
                                TaskModuleIds.TARGET_REF,
                                "coordinate:" + coordinateId
                        )
                )
        );
    }

    private static WorldObserver worldAt(
            AtomicReference<BlockPosition> player
    ) {
        return () -> new WorldObservation(
                true,
                true,
                "minecraft:overworld",
                Optional.of(player.get())
        );
    }

    private static ContainerTarget target(int x, int y, int z, String type) {
        return new ContainerTarget(new BlockPosition(x, y, z), type);
    }

    private static final class RecordingInspection
            implements ContainerInspectionPort {
        private final ContainerTarget expected;
        private ContainerTarget observed;

        private RecordingInspection(ContainerTarget expected) {
            this.expected = expected;
        }

        @Override
        public ContainerInspectionResult startInspection(
                ContainerTarget target
        ) {
            observed = target;
            if (!expected.equals(target)) {
                return ContainerInspectionResult.failed("unexpected target");
            }
            return ContainerInspectionResult.success(
                    "read",
                    Set.of("minecraft:iron_ingot")
            );
        }

        @Override
        public ContainerInspectionResult pollInspection() {
            return ContainerInspectionResult.failed("not pending");
        }

        @Override
        public void cancelInspection() {
        }

        private ContainerTarget observedTarget() {
            return observed;
        }
    }

    private static final class RecordingMarkerCache
            implements CategoryMarkerCachePort {
        private boolean saved;

        @Override
        public Optional<Map<BlockPosition, Set<String>>> contents(UUID bindingId) {
            return Optional.empty();
        }

        @Override
        public void replaceBindings(
                Map<UUID, Map<BlockPosition, Set<String>>> contents
        ) {
            saved = true;
        }
    }

    private static final class ToggleNavigationService
            implements NavigationService {
        private BlockPosition exactTarget;
        private boolean navigating;
        private int cancellations;

        @Override
        public void navigateTo(BlockPosition target) {
            exactTarget = target;
        }

        @Override
        public void navigateToNear(BlockPosition target, int radius) {
            throw new AssertionError("coordinate moves must use exact navigation");
        }

        @Override
        public void navigateToWithinRadius(BlockPosition target, int radius) {
            throw new AssertionError("coordinate moves must use exact navigation");
        }

        @Override
        public void cancel() {
            cancellations++;
            navigating = false;
        }

        @Override
        public boolean isNavigatingOrCalculating() {
            return navigating;
        }
    }

    private static final class RecordingMoveNavigation implements NavigationPort {
        private final List<BlockPosition> exactPositions = new ArrayList<>();
        private final List<BlockPosition> coordinatePositions = new ArrayList<>();
        private final List<Integer> coordinateRadii = new ArrayList<>();
        private final List<BlockPosition> containerPositions = new ArrayList<>();
        private final List<BlockPosition> statusPositions = new ArrayList<>();
        private final List<Integer> statusRadii = new ArrayList<>();
        private final List<String> dimensionChecks = new ArrayList<>();
        private BlockPosition interactionTarget;
        private int interactionChecks;
        private int cancellations;

        @Override
        public NavigationStatus start(BlockPosition target) {
            exactPositions.add(target);
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus startInteraction(BlockPosition target, AreaBounds standingArea) {
            containerPositions.add(target);
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus startWithinRadius(BlockPosition target, int radius) {
            coordinatePositions.add(target);
            coordinateRadii.add(radius);
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target, Integer radius) {
            statusPositions.add(target);
            statusRadii.add(radius);
            return NavigationStatus.ARRIVED;
        }

        @Override
        public boolean isInDimension(String dimension) {
            dimensionChecks.add(dimension);
            return true;
        }

        @Override
        public boolean isWithinInteractionRange(BlockPosition target) {
            interactionChecks++;
            return target.equals(interactionTarget);
        }

        @Override
        public void cancel() {
            cancellations++;
        }

        @Override
        public StopOutcome requestStop() {
            cancel();
            return StopOutcome.CLEAN;
        }

        private List<BlockPosition> exactPositions() {
            return List.copyOf(exactPositions);
        }

        private List<BlockPosition> coordinatePositions() {
            return List.copyOf(coordinatePositions);
        }

        private List<Integer> coordinateRadii() {
            return List.copyOf(coordinateRadii);
        }

        private List<BlockPosition> containerPositions() {
            return List.copyOf(containerPositions);
        }

        private List<BlockPosition> statusPositions() {
            return List.copyOf(statusPositions);
        }

        private List<Integer> statusRadii() {
            return new ArrayList<>(statusRadii);
        }

        private List<String> dimensionChecks() {
            return List.copyOf(dimensionChecks);
        }

        private int interactionChecks() {
            return interactionChecks;
        }

        private int cancellations() {
            return cancellations;
        }
    }

    private static final class ForbiddenNavigation implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            throw new AssertionError("basic transfer actions must not navigate");
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            throw new AssertionError("basic transfer actions must not navigate");
        }

        @Override
        public void cancel() {
        }

        @Override
        public StopOutcome requestStop() {
            return StopOutcome.CLEAN;
        }
    }

    private static final class FixedTransfers implements ContainerTransferPort {
        private final ContainerTransferResult result;

        private FixedTransfers(ContainerTransferResult result) {
            this.result = result;
        }

        @Override
        public ContainerTransferResult startDeposit(
                ContainerTarget target,
                Map<String, Integer> items
        ) {
            return result;
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            return result;
        }

        @Override
        public void cancelDeposit() {
        }
    }

    private static final class RecordingTransfers implements ContainerTransferPort {
        private Map<String, Integer> requested = Map.of();

        @Override
        public ContainerTransferResult startDeposit(
                ContainerTarget target,
                Map<String, Integer> items
        ) {
            requested = Map.copyOf(items);
            return ContainerTransferResult.succeeded("deposited", Map.of());
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            return ContainerTransferResult.succeeded("deposited", Map.of());
        }

        @Override
        public void cancelDeposit() {
        }
    }

    private static final class PartialWithdrawal
            implements ContainerWithdrawalPort {
        private boolean started;

        @Override
        public ContainerWithdrawalResult startWithdrawal(
                ContainerTarget target,
                Set<String> matchingItemIds
        ) {
            started = true;
            return ContainerWithdrawalResult.pending("pending");
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            if (!started) {
                return ContainerWithdrawalResult.failed("not started", Map.of(),
                        false);
            }
            started = false;
            return ContainerWithdrawalResult.failed(
                    "partial",
                    Map.of("minecraft:iron_ingot", 2),
                    false
            );
        }

        @Override
        public void cancelWithdrawal() {
            throw new AssertionError("in-flight withdrawal must be drained");
        }
    }

    private static final class CapturingWithdrawal
            implements ContainerWithdrawalPort {
        private Set<String> matchingItemIds = Set.of();
        private Set<String> fullBoxCategories = Set.of();

        @Override
        public ContainerWithdrawalResult startClassificationBatch(ContainerTarget target,
                Set<String> matchingItemIds, Set<String> fullBoxCategories) {
            this.fullBoxCategories = Set.copyOf(fullBoxCategories);
            return startWithdrawal(target, matchingItemIds);
        }

        @Override
        public ContainerWithdrawalResult startWithdrawal(
                ContainerTarget target,
                Set<String> matchingItemIds
        ) {
            this.matchingItemIds = Set.copyOf(matchingItemIds);
            return ContainerWithdrawalResult.succeeded(
                    "withdrawn",
                    Map.of(),
                    false
            );
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            return ContainerWithdrawalResult.failed(
                    "not pending",
                    Map.of(),
                    false
            );
        }

        @Override
        public void cancelWithdrawal() {
        }

        private Set<String> matchingItemIds() {
            return matchingItemIds;
        }
    }

    private static final class FailedWithdrawals
            implements ContainerWithdrawalPort {
        @Override
        public ContainerWithdrawalResult startWithdrawal(
                ContainerTarget target,
                Set<String> matchingItemIds
        ) {
            return ContainerWithdrawalResult.failed(
                    "unused",
                    Map.of(),
                    false
            );
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            return ContainerWithdrawalResult.failed(
                    "unused",
                    Map.of(),
                    false
            );
        }

        @Override
        public void cancelWithdrawal() {
        }
    }

    private static final class AcquiringFoodPort
            implements FoodConsumptionPort {
        private boolean acquiring;

        @Override
        public FoodStatus observe() {
            return new FoodStatus(true, 10, 0);
        }

        @Override
        public FoodConsumptionResult startEating(String itemId) {
            return FoodConsumptionResult.pending("eating");
        }

        @Override
        public FoodConsumptionResult pollEating() {
            return FoodConsumptionResult.succeeded("eaten");
        }

        @Override
        public void cancelEating() {
        }

        @Override
        public FoodConsumptionResult startAcquiringFood(
                ContainerTarget target,
                String itemId
        ) {
            acquiring = true;
            return FoodConsumptionResult.pending("acquiring");
        }

        @Override
        public FoodConsumptionResult pollAcquiringFood() {
            if (!acquiring) {
                return FoodConsumptionResult.failed("not acquiring");
            }
            acquiring = false;
            return FoodConsumptionResult.succeeded(
                    "acquired",
                    Map.of("minecraft:bread", 1)
            );
        }

        @Override
        public void cancelAcquiringFood() {
            acquiring = false;
        }
    }

    private static final class FoodPort implements FoodConsumptionPort {
        @Override
        public FoodStatus observe() {
            return new FoodStatus(true, 10, 0);
        }

        @Override
        public FoodConsumptionResult startEating(String itemId) {
            return FoodConsumptionResult.pending("eating");
        }

        @Override
        public FoodConsumptionResult pollEating() {
            return FoodConsumptionResult.succeeded("eaten");
        }

        @Override
        public void cancelEating() {
        }
    }
}
