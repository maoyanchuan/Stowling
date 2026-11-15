package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerRegionPort;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferPort;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
                        ConfigValidator.COORDINATE_ID,
                        point.id().toString()
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
                transfers,
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
        return new TaskExecutionContext(
                navigation,
                dropped,
                regions,
                transfers,
                Map.of(point.id(), point),
                Map.of(region.id(), region),
                workspace
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
}
