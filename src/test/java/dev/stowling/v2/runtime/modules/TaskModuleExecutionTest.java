package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.automation.ContainerCategory;
import dev.stowling.automation.ContainerCategoryPort;
import dev.stowling.automation.ContainerCategoryResult;
import dev.stowling.automation.ContainerInspectionPort;
import dev.stowling.automation.ContainerInspectionResult;
import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerWithdrawalPort;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.CategoryZonePort;
import dev.stowling.automation.CategoryZoneResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.FoodConsumptionPort;
import dev.stowling.automation.FoodConsumptionResult;
import dev.stowling.automation.FoodStatus;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.category.ContainerContentsCategoryRule;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskModuleExecutionTest {
    @Test
    void waitModuleStaysWaitingUntilMatchingItemsAppear() {
        CoordinatePoint point = point("Drop");
        MutableDroppedItems dropped = new MutableDroppedItems();
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                dropped,
                new EmptyRegions(),
                new RecordingTransfers(),
                point,
                region("Storage")
        );
        WaitForDroppedItemsModule module = new WaitForDroppedItemsModule(
                context,
                step(
                        ConfigValidator.WAIT_FOR_DROPPED_ITEMS,
                        ConfigValidator.COORDINATE_ID,
                        point.id().toString()
                )
        );

        assertEquals(TaskModuleState.WAITING, module.start().state());
        dropped.items.add(new DroppedItem(
                UUID.randomUUID(),
                "minecraft:iron_ingot",
                3,
                new BlockPosition(1, 64, 1)
        ));

        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
    }

    @Test
    void collectModuleRecordsEveryPickedUpStackInTheRunWorkspace() {
        CoordinatePoint point = point("Drop");
        MutableDroppedItems dropped = new MutableDroppedItems();
        DroppedItem first = drop("minecraft:iron_ingot", 3);
        DroppedItem second = drop("minecraft:gold_ingot", 2);
        dropped.items.add(first);
        dropped.items.add(second);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                dropped,
                new EmptyRegions(),
                new RecordingTransfers(),
                point,
                region("Storage"),
                workspace
        );
        CollectDroppedItemsModule module = new CollectDroppedItemsModule(
                context,
                step(
                        ConfigValidator.COLLECT_DROPPED_ITEMS,
                        ConfigValidator.AREA_REFERENCE,
                        "coordinate:" + point.id()
                )
        );

        module.start();
        dropped.items.remove(first);
        module.tick();
        module.tick();
        dropped.items.remove(second);
        module.tick();
        TaskModuleResult result = module.tick();
        for (int tick = 0;
                tick < 10 && result.state() != TaskModuleState.SUCCEEDED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                Map.of("minecraft:iron_ingot", 3, "minecraft:gold_ingot", 2),
                workspace.collectedItems()
        );
    }

    @Test
    void collectModuleRecordsAllStacksPickedUpInTheSameTick() {
        CoordinatePoint point = point("Drop");
        MutableDroppedItems dropped = new MutableDroppedItems();
        dropped.inventory = Optional.of(Map.of());
        dropped.items.add(drop("minecraft:iron_ingot", 64));
        dropped.items.add(drop("minecraft:gold_ingot", 64));
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                dropped,
                new EmptyRegions(),
                new RecordingTransfers(),
                point,
                region("Storage"),
                workspace
        );
        CollectDroppedItemsModule module = new CollectDroppedItemsModule(
                context,
                step(
                        ConfigValidator.COLLECT_DROPPED_ITEMS,
                        ConfigValidator.COORDINATE_ID,
                        point.id().toString()
                )
        );

        module.start();
        dropped.items.clear();
        dropped.inventory = Optional.of(Map.of(
                "minecraft:iron_ingot",
                64,
                "minecraft:gold_ingot",
                64
        ));
        TaskModuleResult result = module.tick();
        for (int tick = 0;
                tick < 10 && result.state() != TaskModuleState.SUCCEEDED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                Map.of("minecraft:iron_ingot", 64, "minecraft:gold_ingot", 64),
                workspace.collectedItems()
        );
    }

    @Test
    void scanAndDepositShareOnlyPreviousScanAndCurrentRunItems() {
        RegionDefinition region = region("Storage");
        ContainerTarget target = target(0);
        FixedRegions regions = new FixedRegions(
                ContainerScanResult.success(List.of(target))
        );
        RecordingTransfers transfers = new RecordingTransfers();
        transfers.results.add(ContainerTransferResult.pending(
                "opening",
                Map.of("minecraft:iron_ingot", 3)
        ));
        transfers.pollResults.add(ContainerTransferResult.succeeded(
                "stored",
                Map.of()
        ));
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.recordCollected("minecraft:iron_ingot", 3);
        workspace.setContainerCategories(
                ContainerCategoryResult.success(List.of())
        );
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                regions,
                transfers,
                point("Drop"),
                region,
                workspace
        );

        ScanContainersInRegionModule scan = new ScanContainersInRegionModule(
                context,
                step(
                        ConfigValidator.SCAN_CONTAINERS_IN_REGION,
                        ConfigValidator.REGION_ID,
                        region.id().toString()
                )
        );
        assertEquals(TaskModuleState.SUCCEEDED, scan.start().state());
        assertTrue(workspace.containerScan().isPresent());
        assertEquals(
                region.id(),
                workspace.containerScanRegionId().orElseThrow()
        );
        assertTrue(workspace.containerCategories().isEmpty());

        DepositItemsToContainersModule deposit =
                new DepositItemsToContainersModule(
                        context,
                        depositStep()
                );
        assertEquals(TaskModuleState.WAITING, deposit.start().state());
        assertEquals(
                Map.of("minecraft:iron_ingot", 3),
                transfers.requested.getFirst()
        );
        assertEquals(TaskModuleState.SUCCEEDED, deposit.tick().state());
        assertEquals(
                Map.of(region.id(), 3),
                workspace.consumeCacheUpdates()
        );
    }

    @Test
    void matchModuleStoresCategoriesFromThePreviousScanInThisRun() {
        RegionDefinition region = region("Storage");
        ContainerTarget target = target(0);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setContainerScan(ContainerScanResult.success(List.of(target)));
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                new EmptyRegions(),
                containers -> ContainerCategoryResult.success(List.of(
                        new ContainerCategory(
                                containers.getFirst(),
                                "minecraft:iron_ingot"
                        )
                )),
                new RecordingTransfers(),
                new NoWithdrawals(),
                point("Drop"),
                region,
                workspace
        );
        MatchContainerCategoriesModule module =
                new MatchContainerCategoriesModule(
                        context,
                        step(
                                ConfigValidator.MATCH_CONTAINER_CATEGORIES,
                                ConfigValidator.SCAN_SOURCE,
                                "previous-scan"
                        )
                );

        TaskModuleResult result = module.start();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                List.of(new ContainerCategory(
                        target,
                        "minecraft:iron_ingot"
                )),
                workspace.containerCategories().orElseThrow().categories()
        );
    }

    @Test
    void depositSendsOnlyMatchingItemsToAnExactCategoryContainer() {
        ContainerTarget ironChest = target(0);
        ContainerTarget goldChest = target(1);
        ContainerTarget unclassified = target(2);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setContainerScan(ContainerScanResult.success(List.of(
                ironChest,
                goldChest,
                unclassified
        )));
        workspace.setContainerCategories(ContainerCategoryResult.success(
                List.of(
                        new ContainerCategory(
                                ironChest,
                                "minecraft:iron_ingot"
                        ),
                        new ContainerCategory(
                                goldChest,
                                "minecraft:gold_ingot"
                        )
                )
        ));
        workspace.recordCollected("minecraft:iron_ingot", 2);
        workspace.recordCollected("minecraft:gold_ingot", 1);
        RecordingTransfers transfers = new RecordingTransfers();
        transfers.results.add(ContainerTransferResult.pending(
                "opening iron chest",
                Map.of("minecraft:gold_ingot", 1)
        ));
        transfers.pollResults.add(ContainerTransferResult.succeeded(
                "stored iron",
                Map.of("minecraft:gold_ingot", 1)
        ));
        transfers.results.add(ContainerTransferResult.pending(
                "opening gold chest",
                Map.of()
        ));
        transfers.pollResults.add(ContainerTransferResult.succeeded(
                "stored gold",
                Map.of()
        ));
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                new EmptyRegions(),
                new NoCategories(),
                transfers,
                new NoWithdrawals(),
                point("Drop"),
                region("Storage"),
                workspace
        );
        DepositItemsToContainersModule module =
                new DepositItemsToContainersModule(
                        context,
                        depositStep()
                );

        TaskModuleResult result = module.start();
        for (int tick = 0;
                tick < 20 && result.state() != TaskModuleState.SUCCEEDED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                List.of(
                        Map.of("minecraft:iron_ingot", 2),
                        Map.of("minecraft:gold_ingot", 1)
                ),
                transfers.requested
        );
        assertTrue(workspace.collectedItems().isEmpty());
        assertTrue(workspace.consumeCacheUpdates().isEmpty());
    }

    @Test
    void collectRegionModuleRecordsEveryDropInsideTheRegion() {
        RegionDefinition source = region("Source");
        MutableDroppedItems dropped = new MutableDroppedItems();
        DroppedItem first = drop("minecraft:iron_ingot", 3);
        DroppedItem second = drop("minecraft:gold_ingot", 2);
        dropped.items.add(first);
        dropped.items.add(second);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                dropped,
                new EmptyRegions(),
                new NoCategories(),
                new RecordingTransfers(),
                new NoWithdrawals(),
                point("Drop"),
                source,
                workspace
        );
        CollectDroppedItemsModule module =
                new CollectDroppedItemsModule(
                        context,
                        step(
                                ConfigValidator.COLLECT_DROPPED_ITEMS,
                                ConfigValidator.AREA_REFERENCE,
                                "region:" + source.id()
                        )
                );

        module.start();
        dropped.items.remove(first);
        module.tick();
        dropped.items.remove(second);
        TaskModuleResult result = module.tick();
        for (int tick = 0;
                tick < 20 && result.state() != TaskModuleState.SUCCEEDED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                Map.of("minecraft:iron_ingot", 3, "minecraft:gold_ingot", 2),
                workspace.collectedItems()
        );
    }

    @Test
    void sortCacheModuleWithdrawsOnlyCategorizedItemsForExactTargets() {
        ContainerTarget ironTarget = target(0);
        ContainerTarget goldTarget = target(1);
        ContainerTarget unclassified = target(2);
        ContainerTarget cache = target(3);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setContainerScan(ContainerScanResult.success(List.of(
                ironTarget,
                goldTarget,
                unclassified
        )));
        workspace.setContainerCategories(ContainerCategoryResult.success(
                List.of(
                        new ContainerCategory(
                                ironTarget,
                                "minecraft:iron_ingot"
                        ),
                        new ContainerCategory(
                                goldTarget,
                                "minecraft:gold_ingot"
                        )
                )
        ));
        RecordingWithdrawals withdrawals = new RecordingWithdrawals();
        withdrawals.results.add(ContainerWithdrawalResult.succeeded(
                "withdrawn",
                Map.of("minecraft:iron_ingot", 2, "minecraft:gold_ingot", 1),
                false
        ));
        RecordingTransfers transfers = new RecordingTransfers();
        transfers.results.add(ContainerTransferResult.pending(
                "depositing iron",
                Map.of()
        ));
        transfers.pollResults.add(ContainerTransferResult.succeeded(
                "stored iron",
                Map.of()
        ));
        transfers.results.add(ContainerTransferResult.pending(
                "depositing gold",
                Map.of()
        ));
        transfers.pollResults.add(ContainerTransferResult.succeeded(
                "stored gold",
                Map.of()
        ));
        RegionDefinition cacheRegion = region("Cache");
        RecordingNearNavigation navigation = new RecordingNearNavigation();
        TaskExecutionContext context = context(
                navigation,
                new MutableDroppedItems(),
                new FixedRegions(ContainerScanResult.success(List.of(cache))),
                new NoCategories(),
                transfers,
                withdrawals,
                point("Drop"),
                cacheRegion,
                workspace
        );
        SortCacheItemsModule module = new SortCacheItemsModule(
                context,
                new TaskStep(
                        UUID.randomUUID(),
                        ConfigValidator.SORT_CACHE_ITEMS,
                        Map.of(
                                ConfigValidator.CACHE_REGION_ID,
                                cacheRegion.id().toString(),
                                ConfigValidator.SCAN_SOURCE,
                                "previous-scan"
                        )
                )
        );

        TaskModuleResult result = module.start();
        for (int tick = 0;
                tick < 40 && result.state() != TaskModuleState.SUCCEEDED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                Set.of("minecraft:iron_ingot", "minecraft:gold_ingot"),
                withdrawals.requested.getFirst()
        );
        assertEquals(
                List.of(
                        Map.of("minecraft:iron_ingot", 2),
                        Map.of("minecraft:gold_ingot", 1)
                ),
                transfers.requested
        );
        assertEquals(
                List.of(
                        cache.position(),
                        ironTarget.position(),
                        goldTarget.position()
                ),
                navigation.started
        );
        assertTrue(navigation.radii.stream().allMatch(radius -> radius == 4));
    }

    @Test
    void sortCacheModuleSkipsUnreachableStorageContainer() {
        ContainerTarget unreachable = target(0);
        ContainerTarget reachable = target(1);
        ContainerTarget cache = target(2);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setContainerScan(ContainerScanResult.success(List.of(
                unreachable,
                reachable
        )));
        workspace.setContainerCategories(ContainerCategoryResult.success(
                List.of(
                        new ContainerCategory(
                                unreachable,
                                "minecraft:iron_ingot"
                        ),
                        new ContainerCategory(
                                reachable,
                                "minecraft:iron_ingot"
                        )
                )
        ));
        RecordingWithdrawals withdrawals = new RecordingWithdrawals();
        withdrawals.results.add(ContainerWithdrawalResult.succeeded(
                "withdrawn",
                Map.of("minecraft:iron_ingot", 1),
                false
        ));
        RecordingTransfers transfers = new RecordingTransfers();
        transfers.results.add(ContainerTransferResult.pending(
                "depositing iron",
                Map.of()
        ));
        transfers.pollResults.add(ContainerTransferResult.succeeded(
                "stored iron",
                Map.of()
        ));
        RegionDefinition cacheRegion = region("Cache");
        RecordingNearNavigation navigation =
                new RecordingNearNavigation(unreachable.position());
        TaskExecutionContext context = context(
                navigation,
                new MutableDroppedItems(),
                new FixedRegions(ContainerScanResult.success(List.of(cache))),
                new NoCategories(),
                transfers,
                withdrawals,
                point("Drop"),
                cacheRegion,
                workspace
        );
        SortCacheItemsModule module = new SortCacheItemsModule(
                context,
                new TaskStep(
                        UUID.randomUUID(),
                        ConfigValidator.SORT_CACHE_ITEMS,
                        Map.of(
                                ConfigValidator.CACHE_REGION_ID,
                                cacheRegion.id().toString(),
                                ConfigValidator.SCAN_SOURCE,
                                "previous-scan"
                        )
                )
        );

        TaskModuleResult result = module.start();
        for (int tick = 0;
                tick < 20 && result.state() != TaskModuleState.SUCCEEDED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                List.of(
                        cache.position(),
                        unreachable.position(),
                        reachable.position()
                ),
                navigation.started
        );
        assertEquals(
                List.of(Map.of("minecraft:iron_ingot", 1)),
                transfers.requested
        );
    }

    @Test
    void sortCacheModuleReturnsUndepositedItemsToTheCache() {
        ContainerTarget ironTarget = target(0);
        ContainerTarget cache = target(1);
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setContainerScan(ContainerScanResult.success(List.of(
                ironTarget
        )));
        workspace.setContainerCategories(ContainerCategoryResult.success(
                List.of(new ContainerCategory(
                        ironTarget,
                        "minecraft:iron_ingot"
                ))
        ));
        RecordingWithdrawals withdrawals = new RecordingWithdrawals();
        withdrawals.results.add(ContainerWithdrawalResult.succeeded(
                "withdrawn",
                Map.of("minecraft:iron_ingot", 2),
                false
        ));
        RecordingTransfers transfers = new RecordingTransfers();
        transfers.results.add(ContainerTransferResult.pending(
                "depositing iron",
                Map.of("minecraft:iron_ingot", 2)
        ));
        transfers.pollResults.add(ContainerTransferResult.failed(
                "target full",
                Map.of("minecraft:iron_ingot", 2)
        ));
        transfers.results.add(ContainerTransferResult.pending(
                "returning iron",
                Map.of("minecraft:iron_ingot", 2)
        ));
        transfers.pollResults.add(ContainerTransferResult.succeeded(
                "returned iron",
                Map.of()
        ));
        RegionDefinition cacheRegion = region("Cache");
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                new FixedRegions(ContainerScanResult.success(List.of(cache))),
                new NoCategories(),
                transfers,
                withdrawals,
                point("Drop"),
                cacheRegion,
                workspace
        );
        SortCacheItemsModule module = new SortCacheItemsModule(
                context,
                new TaskStep(
                        UUID.randomUUID(),
                        ConfigValidator.SORT_CACHE_ITEMS,
                        Map.of(
                                ConfigValidator.CACHE_REGION_ID,
                                cacheRegion.id().toString(),
                                ConfigValidator.SCAN_SOURCE,
                                "previous-scan"
                        )
                )
        );

        TaskModuleResult result = module.start();
        for (int tick = 0;
                tick < 40 && result.state() != TaskModuleState.FAILED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("退回缓存"));
        assertEquals(
                List.of(
                        Map.of("minecraft:iron_ingot", 2),
                        Map.of("minecraft:iron_ingot", 2)
                ),
                transfers.requested
        );
    }

    @Test
    void categoryZoneSortUsesBlockIdExactMapping() {
        RegionDefinition cacheRegion = region("Cache");
        ContainerTarget storage = target(0);
        ContainerTarget cache = target(1);
        CategoryZoneDefinition zone = new CategoryZoneDefinition(
                UUID.randomUUID(),
                "A",
                List.of(new CategoryBindingDefinition(
                        UUID.randomUUID(),
                        new AreaBounds(7, -34, 7, 18, -34, 7),
                        new AreaBounds(7, -34, 5, 18, -34, 6),
                        "DIRECT_BLOCK_ID"
                ))
        );
        RecordingWithdrawals withdrawals = new RecordingWithdrawals();
        withdrawals.results.add(ContainerWithdrawalResult.succeeded(
                "withdrawn",
                Map.of(
                        "minecraft:iron_block",
                        2,
                        "minecraft:gold_block",
                        1
                ),
                false
        ));
        RecordingTransfers transfers = new RecordingTransfers();
        transfers.results.add(ContainerTransferResult.pending(
                "depositing",
                Map.of()
        ));
        transfers.pollResults.add(ContainerTransferResult.succeeded(
                "stored",
                Map.of()
        ));
        TaskExecutionContext context = context(
                new BrittleAfterArrivalNavigation(cache.position()),
                new MutableDroppedItems(),
                new FixedRegions(ContainerScanResult.success(List.of(cache))),
                new NoCategories(),
                transfers,
                withdrawals,
                (ignored, types, maxVolume) -> CategoryZoneResult.success(
                        Map.of(
                                "minecraft:iron_block",
                                List.of(storage),
                                "minecraft:gold_block",
                                List.of(storage)
                        )
                ),
                Map.of(zone.id(), zone),
                point("Drop"),
                cacheRegion,
                new TaskRunWorkspace()
        );
        SortCacheItemsByCategoryZoneModule module =
                new SortCacheItemsByCategoryZoneModule(
                        context,
                        new TaskStep(
                                UUID.randomUUID(),
                                ConfigValidator
                                        .SORT_CACHE_ITEMS_BY_CATEGORY_ZONE,
                                Map.of(
                                        ConfigValidator.CACHE_REGION_ID,
                                        cacheRegion.id().toString(),
                                        ConfigValidator.CATEGORY_ZONE_ID,
                                        zone.id().toString()
                                )
                        )
                );

        TaskModuleResult result = module.start();
        for (int tick = 0;
                tick < 30 && result.state() != TaskModuleState.SUCCEEDED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                Map.of(
                        "minecraft:iron_block",
                        2,
                        "minecraft:gold_block",
                        1
                ),
                transfers.requested.getFirst()
        );
    }

    @Test
    void refreshCategoryMarkerCacheStoresInspectedContainerItems() {
        RegionDefinition cacheRegion = region("Cache");
        CoordinatePoint point = point("Drop");
        CategoryBindingDefinition binding = new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(7, -34, 7, 7, -34, 7),
                new AreaBounds(7, -34, 5, 7, -34, 5),
                ContainerContentsCategoryRule.ID
        );
        CategoryZoneDefinition zone = new CategoryZoneDefinition(
                UUID.randomUUID(),
                "A",
                List.of(binding)
        );
        ContainerTarget marker = new ContainerTarget(
                new BlockPosition(7, -34, 7),
                "minecraft:barrel"
        );
        RecordingInspection inspections = new RecordingInspection(Set.of(
                "minecraft:oak_log",
                "minecraft:oak_planks"
        ));
        RecordingCategoryMarkerCache cache =
                new RecordingCategoryMarkerCache();
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                new FixedRegions(ContainerScanResult.success(
                        List.of(marker)
                )),
                new NoCategories(),
                new RecordingTransfers(),
                new NoWithdrawals(),
                new NoCategoryZones(),
                Map.of(zone.id(), zone),
                point,
                cacheRegion,
                new TaskRunWorkspace(),
                inspections,
                cache
        );
        RefreshCategoryMarkerCacheModule module =
                new RefreshCategoryMarkerCacheModule(
                        context,
                        step(
                                ConfigValidator
                                        .REFRESH_CATEGORY_MARKER_CACHE,
                                ConfigValidator.CATEGORY_ZONE_ID,
                                zone.id().toString()
                        )
                );

        TaskModuleResult result = module.start();
        for (int tick = 0;
                tick < 10 && result.state() == TaskModuleState.WAITING;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                Set.of("minecraft:oak_log", "minecraft:oak_planks"),
                cache.saved.get(binding.id()).get(marker.position())
        );
    }

    @Test
    void autoEatSkipsNavigationWhenFoodLevelIsSufficient() {
        CoordinatePoint point = point("Eating");
        RecordingFood food = new RecordingFood(20, 18);
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                new EmptyRegions(),
                new NoCategories(),
                new RecordingTransfers(),
                new NoWithdrawals(),
                new NoCategoryZones(),
                Map.of(),
                point,
                region("Storage"),
                new TaskRunWorkspace(),
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                food
        );
        AutoEatAtCoordinateModule module = new AutoEatAtCoordinateModule(
                context,
                autoEatStep(point, "minecraft:bread", "18")
        );

        TaskModuleResult result = module.start();

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertTrue(food.requested.isEmpty());
        assertTrue(food.acquired.isEmpty());
        assertTrue(food.events.isEmpty());
    }

    @Test
    void autoEatNavigatesAndConsumesFoodBelowThreshold() {
        CoordinatePoint point = point("Eating");
        RecordingFood food = new RecordingFood(10, 18);
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                new EmptyRegions(),
                new NoCategories(),
                new RecordingTransfers(),
                new NoWithdrawals(),
                new NoCategoryZones(),
                Map.of(),
                point,
                region("Storage"),
                new TaskRunWorkspace(),
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                food
        );
        AutoEatAtCoordinateModule module = new AutoEatAtCoordinateModule(
                context,
                autoEatStep(point, "minecraft:cooked_beef", "18")
        );

        TaskModuleResult result = module.start();
        for (int tick = 0;
                tick < 10
                        && result.state() != TaskModuleState.SUCCEEDED
                        && result.state() != TaskModuleState.FAILED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(
                List.of("minecraft:cooked_beef"),
                food.requested
        );
        assertEquals(
                List.of(new BlockPosition(0, 64, 0)),
                food.acquired.stream()
                        .map(ContainerTarget::position)
                        .toList()
        );
        assertEquals(
                List.of(
                        "acquire:minecraft:cooked_beef",
                        "acquired:minecraft:cooked_beef",
                        "eat:minecraft:cooked_beef",
                        "eaten:minecraft:cooked_beef"
                ),
                food.events
        );
    }

    @Test
    void autoEatFetchesAnotherFoodWhenOneItemIsNotEnough() {
        CoordinatePoint point = point("Eating");
        RecordingFood food = new RecordingFood(10, 2, 18);
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                new EmptyRegions(),
                new NoCategories(),
                new RecordingTransfers(),
                new NoWithdrawals(),
                new NoCategoryZones(),
                Map.of(),
                point,
                region("Storage"),
                new TaskRunWorkspace(),
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                food
        );
        AutoEatAtCoordinateModule module = new AutoEatAtCoordinateModule(
                context,
                autoEatStep(point, "minecraft:bread", "18")
        );

        TaskModuleResult result = module.start();
        for (int tick = 0;
                tick < 20
                        && result.state() != TaskModuleState.SUCCEEDED
                        && result.state() != TaskModuleState.FAILED;
                tick++) {
            result = module.tick();
        }

        assertEquals(TaskModuleState.SUCCEEDED, result.state());
        assertEquals(2, food.acquired.size());
        assertEquals(
                List.of("minecraft:bread", "minecraft:bread"),
                food.requested
        );
        assertEquals(
                List.of(
                        "acquire:minecraft:bread",
                        "acquired:minecraft:bread",
                        "eat:minecraft:bread",
                        "eaten:minecraft:bread",
                        "acquire:minecraft:bread",
                        "acquired:minecraft:bread",
                        "eat:minecraft:bread",
                        "eaten:minecraft:bread"
                ),
                food.events
        );
    }

    @Test
    void eatFoodModuleConsumesTheConfiguredInventoryFood() {
        CoordinatePoint point = point("Eating");
        RecordingFood food = new RecordingFood(10, 10);
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                new EmptyRegions(),
                new NoCategories(),
                new RecordingTransfers(),
                new NoWithdrawals(),
                new NoCategoryZones(),
                Map.of(),
                point,
                region("Storage"),
                new TaskRunWorkspace(),
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                food
        );
        EatFoodModule module = new EatFoodModule(
                context,
                step(
                        ConfigValidator.EAT_FOOD,
                        ConfigValidator.FOOD_ITEM_ID,
                        "minecraft:bread"
                )
        );

        assertEquals(TaskModuleState.WAITING, module.start().state());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertEquals(List.of("minecraft:bread"), food.requested);
        assertEquals(
                List.of("eat:minecraft:bread", "eaten:minecraft:bread"),
                food.events
        );
    }

    @Test
    void withdrawFoodModuleAcquiresTheConfiguredFood() {
        CoordinatePoint point = point("Eating");
        RecordingFood food = new RecordingFood(10, 10);
        TaskExecutionContext context = context(
                new ArrivedNavigation(),
                new MutableDroppedItems(),
                new EmptyRegions(),
                new NoCategories(),
                new RecordingTransfers(),
                new NoWithdrawals(),
                new NoCategoryZones(),
                Map.of(),
                point,
                region("Storage"),
                new TaskRunWorkspace(),
                ContainerInspectionPort.UNAVAILABLE,
                CategoryMarkerCachePort.UNAVAILABLE,
                food
        );
        WithdrawFoodModule module = new WithdrawFoodModule(
                context,
                step(
                        ConfigValidator.WITHDRAW_FOOD,
                        ConfigValidator.COORDINATE_ID,
                        point.id().toString(),
                        ConfigValidator.FOOD_ITEM_ID,
                        "minecraft:bread"
                )
        );

        assertEquals(TaskModuleState.WAITING, module.start().state());
        assertEquals(TaskModuleState.SUCCEEDED, module.tick().state());
        assertEquals(
                List.of("acquire:minecraft:bread", "acquired:minecraft:bread"),
                food.events
        );
    }

    private static TaskExecutionContext context(
            NavigationPort navigation,
            DroppedItemObserverPort dropped,
            ContainerRegionPort regions,
            ContainerTransferPort transfers,
            CoordinatePoint point,
            RegionDefinition region
    ) {
        return context(
                navigation,
                dropped,
                regions,
                new NoCategories(),
                transfers,
                new NoWithdrawals(),
                point,
                region,
                new TaskRunWorkspace()
        );
    }

    private static TaskExecutionContext context(
            NavigationPort navigation,
            DroppedItemObserverPort dropped,
            ContainerRegionPort regions,
            ContainerTransferPort transfers,
            CoordinatePoint point,
            RegionDefinition region,
            TaskRunWorkspace workspace
    ) {
        return context(
                navigation,
                dropped,
                regions,
                new NoCategories(),
                transfers,
                new NoWithdrawals(),
                point,
                region,
                workspace
        );
    }

    private static TaskExecutionContext context(
            NavigationPort navigation,
            DroppedItemObserverPort dropped,
            ContainerRegionPort regions,
            ContainerCategoryPort categories,
            ContainerTransferPort transfers,
            ContainerWithdrawalPort withdrawals,
            CoordinatePoint point,
            RegionDefinition region,
            TaskRunWorkspace workspace
    ) {
        return context(
                navigation,
                dropped,
                regions,
                categories,
                transfers,
                withdrawals,
                new NoCategoryZones(),
                Map.of(),
                point,
                region,
                workspace
        );
    }

    private static TaskExecutionContext context(
            NavigationPort navigation,
            DroppedItemObserverPort dropped,
            ContainerRegionPort regions,
            ContainerCategoryPort categories,
            ContainerTransferPort transfers,
            ContainerWithdrawalPort withdrawals,
            CategoryZonePort categoryZonesPort,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            CoordinatePoint point,
            RegionDefinition region,
            TaskRunWorkspace workspace
    ) {
        return new TaskExecutionContext(
                navigation,
                dropped,
                regions,
                categories,
                transfers,
                withdrawals,
                categoryZonesPort,
                Map.of(point.id(), point),
                Map.of(region.id(), region),
                categoryZones,
                workspace
        );
    }

    private static TaskExecutionContext context(
            NavigationPort navigation,
            DroppedItemObserverPort dropped,
            ContainerRegionPort regions,
            ContainerCategoryPort categories,
            ContainerTransferPort transfers,
            ContainerWithdrawalPort withdrawals,
            CategoryZonePort categoryZonesPort,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            CoordinatePoint point,
            RegionDefinition region,
            TaskRunWorkspace workspace,
            ContainerInspectionPort inspections,
            CategoryMarkerCachePort cache
    ) {
        return new TaskExecutionContext(
                navigation,
                dropped,
                regions,
                categories,
                transfers,
                withdrawals,
                inspections,
                cache,
                categoryZonesPort,
                Map.of(point.id(), point),
                Map.of(region.id(), region),
                categoryZones,
                workspace
        );
    }

    private static TaskExecutionContext context(
            NavigationPort navigation,
            DroppedItemObserverPort dropped,
            ContainerRegionPort regions,
            ContainerCategoryPort categories,
            ContainerTransferPort transfers,
            ContainerWithdrawalPort withdrawals,
            CategoryZonePort categoryZonesPort,
            Map<UUID, CategoryZoneDefinition> categoryZones,
            CoordinatePoint point,
            RegionDefinition region,
            TaskRunWorkspace workspace,
            ContainerInspectionPort inspections,
            CategoryMarkerCachePort cache,
            FoodConsumptionPort food
    ) {
        return new TaskExecutionContext(
                navigation,
                dropped,
                regions,
                categories,
                transfers,
                withdrawals,
                inspections,
                cache,
                food,
                categoryZonesPort,
                Map.of(point.id(), point),
                Map.of(region.id(), region),
                categoryZones,
                workspace
        );
    }

    private static TaskStep autoEatStep(
            CoordinatePoint point,
            String foodItemId,
            String threshold
    ) {
        return new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.AUTO_EAT_AT_COORDINATE,
                Map.of(
                        ConfigValidator.COORDINATE_ID,
                        point.id().toString(),
                        ConfigValidator.FOOD_ITEM_ID,
                        foodItemId,
                        ConfigValidator.FOOD_LEVEL_THRESHOLD,
                        threshold
                )
        );
    }

    private static TaskStep step(
            String moduleId,
            String key,
            String value
    ) {
        return new TaskStep(
                UUID.randomUUID(),
                moduleId,
                Map.of(key, value)
        );
    }

    private static TaskStep depositStep() {
        return new TaskStep(
                UUID.randomUUID(),
                ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS,
                Map.of(
                        ConfigValidator.SCAN_SOURCE,
                        "previous-scan",
                        ConfigValidator.ITEM_SOURCE,
                        "collected-this-run",
                        ConfigValidator.FILL_MODE,
                        "nearest-first"
                )
        );
    }

    private static CoordinatePoint point(String name) {
        return new CoordinatePoint(UUID.randomUUID(), name, 0, 64, 0);
    }

    private static RegionDefinition region(String name) {
        return new RegionDefinition(
                UUID.randomUUID(),
                name,
                0,
                64,
                0,
                8,
                70,
                8
        );
    }

    private static DroppedItem drop(String itemId, int count) {
        return new DroppedItem(
                UUID.randomUUID(),
                itemId,
                count,
                new BlockPosition(1, 64, 1)
        );
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }

    private static final class MutableDroppedItems
            implements DroppedItemObserverPort {
        private final List<DroppedItem> items = new ArrayList<>();
        private Optional<Map<String, Integer>> inventory = Optional.empty();

        @Override
        public List<DroppedItem> observe(BlockPosition center, int radius) {
            return List.copyOf(items);
        }

        @Override
        public List<DroppedItem> observe(RegionDefinition region) {
            return List.copyOf(items);
        }

        @Override
        public Optional<Map<String, Integer>> playerItemCounts() {
            return inventory;
        }
    }

    private static final class EmptyRegions implements ContainerRegionPort {
        @Override
        public ContainerScanResult scan(
                RegionDefinition region,
                java.util.Set<String> containerTypes,
                int maxVolume
        ) {
            return ContainerScanResult.success(List.of());
        }
    }

    private record FixedRegions(ContainerScanResult result)
            implements ContainerRegionPort {
        @Override
        public ContainerScanResult scan(
                RegionDefinition region,
                java.util.Set<String> containerTypes,
                int maxVolume
        ) {
            return result;
        }
    }

    private static final class RecordingTransfers implements ContainerTransferPort {
        private final List<ContainerTransferResult> results = new ArrayList<>();
        private final List<ContainerTransferResult> pollResults =
                new ArrayList<>();
        private final List<Map<String, Integer>> requested = new ArrayList<>();

        @Override
        public ContainerTransferResult startDeposit(
                ContainerTarget target,
                Map<String, Integer> items
        ) {
            requested.add(Map.copyOf(items));
            return results.removeFirst();
        }

        @Override
        public ContainerTransferResult pollDeposit() {
            return pollResults.removeFirst();
        }

        @Override
        public void cancelDeposit() {
        }
    }

    private static final class NoCategories implements ContainerCategoryPort {
        @Override
        public ContainerCategoryResult match(
                List<ContainerTarget> containers
        ) {
            return ContainerCategoryResult.success(List.of());
        }
    }

    private static final class NoWithdrawals
            implements ContainerWithdrawalPort {
        @Override
        public ContainerWithdrawalResult startWithdrawal(
                ContainerTarget target,
                Set<String> matchingItemIds
        ) {
            return ContainerWithdrawalResult.failed(
                    "withdrawal unavailable",
                    Map.of(),
                    false
            );
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            return ContainerWithdrawalResult.failed(
                    "withdrawal unavailable",
                    Map.of(),
                    false
            );
        }

        @Override
        public void cancelWithdrawal() {
        }
    }

    private static final class NoCategoryZones
            implements CategoryZonePort {
        @Override
        public CategoryZoneResult resolve(
                CategoryZoneDefinition zone,
                Set<String> containerTypes,
                int maxVolume
        ) {
            return CategoryZoneResult.failure("category zones unavailable");
        }
    }

    private static final class RecordingWithdrawals
            implements ContainerWithdrawalPort {
        private final List<ContainerWithdrawalResult> results =
                new ArrayList<>();
        private final List<Set<String>> requested = new ArrayList<>();

        @Override
        public ContainerWithdrawalResult startWithdrawal(
                ContainerTarget target,
                Set<String> matchingItemIds
        ) {
            requested.add(Set.copyOf(matchingItemIds));
            return results.removeFirst();
        }

        @Override
        public ContainerWithdrawalResult pollWithdrawal() {
            return ContainerWithdrawalResult.pending("waiting");
        }

        @Override
        public void cancelWithdrawal() {
        }
    }

    private static final class RecordingInspection
            implements ContainerInspectionPort {
        private final Set<String> itemIds;
        private boolean started;

        private RecordingInspection(Set<String> itemIds) {
            this.itemIds = Set.copyOf(itemIds);
        }

        @Override
        public ContainerInspectionResult startInspection(
                ContainerTarget target
        ) {
            started = true;
            return ContainerInspectionResult.pending("opening");
        }

        @Override
        public ContainerInspectionResult pollInspection() {
            if (!started) {
                return ContainerInspectionResult.failed("not started");
            }
            return ContainerInspectionResult.success("read", itemIds);
        }

        @Override
        public void cancelInspection() {
            started = false;
        }
    }

    private static final class RecordingCategoryMarkerCache
            implements CategoryMarkerCachePort {
        private Map<UUID, Map<BlockPosition, Set<String>>> saved = Map.of();

        @Override
        public Optional<Map<BlockPosition, Set<String>>> contents(
                UUID bindingId
        ) {
            return Optional.ofNullable(saved.get(bindingId));
        }

        @Override
        public void replaceBindings(
                Map<UUID, Map<BlockPosition, Set<String>>> contents
        ) {
            saved = Map.copyOf(contents);
        }
    }

    private static final class RecordingFood
            implements FoodConsumptionPort {
        private final int[] foodAfterMeals;
        private final List<ContainerTarget> acquired = new ArrayList<>();
        private final List<String> events = new ArrayList<>();
        private final List<String> requested = new ArrayList<>();
        private int foodLevel;
        private boolean eating;
        private boolean acquiring;
        private int meals;
        private String acquiringItemId;

        private RecordingFood(int foodLevel, int... foodAfterMeals) {
            this.foodLevel = foodLevel;
            this.foodAfterMeals = foodAfterMeals;
        }

        @Override
        public FoodStatus observe() {
            return new FoodStatus(true, foodLevel, 0.0F);
        }

        @Override
        public FoodConsumptionResult startAcquiringFood(
                ContainerTarget target,
                String itemId
        ) {
            events.add("acquire:" + itemId);
            acquired.add(target);
            acquiring = true;
            acquiringItemId = itemId;
            return FoodConsumptionResult.pending("acquiring");
        }

        @Override
        public FoodConsumptionResult pollAcquiringFood() {
            if (!acquiring) {
                return FoodConsumptionResult.failed("not acquiring");
            }
            acquiring = false;
            events.add("acquired:" + acquiringItemId);
            return FoodConsumptionResult.succeeded("acquired");
        }

        @Override
        public void cancelAcquiringFood() {
            acquiring = false;
        }

        @Override
        public FoodConsumptionResult startEating(String itemId) {
            requested.add(itemId);
            events.add("eat:" + itemId);
            eating = true;
            return FoodConsumptionResult.pending("eating");
        }

        @Override
        public FoodConsumptionResult pollEating() {
            if (!eating) {
                return FoodConsumptionResult.failed("not eating");
            }
            eating = false;
            foodLevel = foodAfterMeals[Math.min(
                    meals,
                    foodAfterMeals.length - 1
            )];
            meals++;
            events.add("eaten:" + activeItemId());
            return FoodConsumptionResult.succeeded("eaten");
        }

        @Override
        public void cancelEating() {
            eating = false;
        }

        private String activeItemId() {
            return requested.get(requested.size() - 1);
        }
    }

    private static final class ArrivedNavigation implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target, Integer radius) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public void cancel() {
        }

        @Override
        public StopOutcome requestStop() {
            return StopOutcome.CLEAN;
        }
    }

    private static final class RecordingNearNavigation implements NavigationPort {
        private final List<BlockPosition> started = new ArrayList<>();
        private final List<Integer> radii = new ArrayList<>();
        private final BlockPosition unreachable;

        private RecordingNearNavigation() {
            this(null);
        }

        private RecordingNearNavigation(BlockPosition unreachable) {
            this.unreachable = unreachable;
        }

        @Override
        public NavigationStatus start(BlockPosition target) {
            throw new AssertionError("exact navigation must not be used");
        }

        @Override
        public NavigationStatus startNear(BlockPosition target, int radius) {
            started.add(target);
            radii.add(radius);
            return target.equals(unreachable)
                    ? NavigationStatus.FAILED
                    : NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target, Integer radius) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public void cancel() {
        }
    }

    private static final class BrittleAfterArrivalNavigation
            implements NavigationPort {
        private final BlockPosition cachePosition;

        private BrittleAfterArrivalNavigation(BlockPosition cachePosition) {
            this.cachePosition = cachePosition;
        }

        @Override
        public NavigationStatus start(BlockPosition target) {
            throw new AssertionError("exact navigation must not be used");
        }

        @Override
        public NavigationStatus startNear(BlockPosition target, int radius) {
            return target.equals(cachePosition)
                    ? NavigationStatus.NAVIGATING
                    : NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return target.equals(cachePosition)
                    ? NavigationStatus.ARRIVED
                    : NavigationStatus.FAILED;
        }

        @Override
        public NavigationStatus status(
                BlockPosition target,
                Integer radius
        ) {
            return status(target);
        }

        @Override
        public void cancel() {
        }
    }
}
