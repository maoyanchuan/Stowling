package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManageCacheIntakeModuleTest {
    private static final String STONE = "minecraft:stone";
    private static final String BOX = "minecraft:shulker_box#packed";
    private static final ContainerTarget CACHE =
            new ContainerTarget(new BlockPosition(0, 64, 0), "minecraft:chest");

    @Test
    void capacityUsesTotalSlotsInsteadOfAveragingEachContainersPercentage() {
        var workspace = new TaskRunWorkspace();
        var barrel = new ContainerTarget(new BlockPosition(1, 64, 0), "minecraft:barrel");
        workspace.recordCacheCapacity(CACHE, 54, 43);
        workspace.recordCacheCapacity(barrel, 27, 21);
        assertFalse(workspace.cacheAtThreshold(List.of(CACHE, barrel), 80));
        workspace.recordCacheCapacity(barrel, 27, 22);
        assertTrue(workspace.cacheAtThreshold(List.of(CACHE, barrel), 80));
        workspace.recordCacheCapacity(CACHE, 54, 1);
        assertFalse(workspace.cacheAtThreshold(List.of(CACHE, barrel), 80));
    }

    @Test
    void belowThresholdStoresLooseItemsAndKeepsCollecting() {
        var scenario = new Scenario(79, false);
        assertEquals(TaskModuleState.SUCCEEDED, scenario.run("PACK_FIRST", true).state());
        assertEquals(List.of("scan", "store"), scenario.events);
        assertTrue(scenario.workspace.managedItems().isEmpty());
    }

    @Test
    void atThresholdPacksBeforeStoringWithoutStartingClassification() {
        var scenario = new Scenario(80, false);
        assertEquals(TaskModuleState.SUCCEEDED, scenario.run("PACK_FIRST", true).state());
        assertEquals(List.of("scan", "pack"), scenario.events);
    }

    @Test
    void packedBoxesThatCannotFitTriggerClassificationInsideTheCollectingRun() {
        var scenario = new Scenario(100, true);
        assertEquals(TaskModuleState.SUCCEEDED, scenario.run("PACK_FIRST", true).state());
        assertEquals(List.of("scan", "pack", "classify", "store"), scenario.events);
        assertTrue(scenario.workspace.managedItems().isEmpty());
    }

    @Test
    void classificationFirstFreesCacheAtTheThresholdEvenWhenTheBatchStillFits() {
        var scenario = new Scenario(80, false);
        assertEquals(TaskModuleState.SUCCEEDED, scenario.run("CLASSIFY_FIRST", true).state());
        assertEquals(List.of("scan", "store", "classify", "store"), scenario.events);
    }

    @Test
    void disabledClassificationKeepsThePackedCargoWhenCacheIsCompletelyFull() {
        var scenario = new Scenario(100, true);
        TaskModuleResult result = scenario.run("PACK_FIRST", false);
        assertEquals(TaskModuleState.FAILED, result.state());
        assertTrue(result.message().contains("请启用分类"));
        assertEquals(Map.of(BOX, 1), scenario.workspace.managedItems());
    }

    private static final class Scenario implements StorageAutomationPort, NavigationPort {
        private final TaskRunWorkspace workspace = new TaskRunWorkspace();
        private final List<String> events = new ArrayList<>();
        private final int occupied;
        private boolean blocked;

        private Scenario(int occupied, boolean blocked) {
            this.occupied = occupied;
            this.blocked = blocked;
            workspace.inventoryAccount().recordCollected(STONE, 64);
            workspace.setContainerScan(ContainerScanResult.success(List.of(CACHE)));
        }

        private TaskModuleResult run(String mode, boolean classificationEnabled) {
            var registry = TaskModuleRuntimeRegistry.unavailablePorts(this);
            var task = new TaskDefinition(UUID.randomUUID(), "Classify", TaskPriority.BACKGROUND,
                    false, List.of(TaskStep.action(UUID.randomUUID(), "MOVE_TO_COORDINATE",
                            Map.of("x", "20", "y", "64", "z", "0"))));
            workspace.configureTasks(Map.of(task.id(), task));
            var inventory = new PlayerInventoryManager(() -> Optional.of(
                    new PlayerInventorySnapshot(workspace.managedItems())));
            var context = new TaskExecutionContext(this, registry.droppedItems(),
                    registry.containerRegions(), registry.containerTransfers(),
                    registry.containerWithdrawals(), registry.containerInspections(),
                    registry.categoryMarkerCache(), registry.foodConsumption(),
                    registry.categoryZones(), Map.of(), Map.of(), Map.of(), workspace,
                    inventory, this);
            var module = new ManageCacheIntakeModule(context, TaskStep.action(UUID.randomUUID(),
                    "MANAGE_CACHE_INTAKE", Map.of("cacheIntakeMode", mode,
                            "cacheThresholdPercent", "80", "classificationTaskRef",
                            classificationEnabled ? "task:" + task.id() : "")));
            TaskModuleResult result = module.start();
            for (int tick = 0; tick < 30 && (result.state() == TaskModuleState.RUNNING
                    || result.state() == TaskModuleState.WAITING); tick++) {
                result = module.tick();
            }
            return result;
        }

        @Override
        public TaskModuleExecutor scanCacheContents(TaskExecutionContext context,
                List<ContainerTarget> containers) {
            return action(() -> {
                events.add("scan");
                workspace.recordCacheCapacity(CACHE, 100, occupied);
            });
        }

        @Override
        public TaskModuleExecutor storeCacheIntake(TaskExecutionContext context,
                List<ContainerTarget> containers, boolean pack) {
            return action(() -> {
                events.add(pack ? "pack" : "store");
                if (pack && workspace.managedItems().containsKey(STONE)) {
                    workspace.consumeDeposit("managed", workspace.managedItems(), Map.of());
                    workspace.inventoryAccount().recordWithdrawn(BOX, 1);
                }
                if (!blocked) {
                    workspace.consumeDeposit("managed", workspace.managedItems(), Map.of());
                }
            });
        }

        private static TaskModuleExecutor action(Runnable body) {
            return new TaskModuleExecutor() {
                @Override
                public TaskModuleResult start() {
                    return tick();
                }

                @Override
                public TaskModuleResult tick() {
                    body.run();
                    return TaskModuleResult.succeeded("done");
                }

                @Override
                public void cancel() {
                }
            };
        }

        @Override
        public NavigationStatus start(BlockPosition target) {
            events.add("classify");
            blocked = false;
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public void cancel() {
        }

        @Override
        public TaskModuleExecutor deposit(TaskExecutionContext context, ContainerTarget target,
                Map<String, Integer> items) {
            throw new AssertionError("unexpected category packing");
        }

        @Override
        public TaskModuleExecutor cleanup(TaskExecutionContext context) {
            throw new AssertionError("unexpected cleanup");
        }
    }
}
