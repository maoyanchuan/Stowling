package dev.stowling.automation.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationClock;
import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationModuleRegistry;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationModuleStatus;
import dev.stowling.automation.AutomationOperationPort;
import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.CoordinateResolver;
import dev.stowling.automation.CraftingPort;
import dev.stowling.automation.InventoryQueryPort;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.automation.MaterialAcquisitionPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PickupWorkflowPort;
import dev.stowling.automation.PlayerInventorySlot;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.domain.BlockPosition;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AutomationModulesTest {
    private static final UUID TASK_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final Instant START = Instant.parse("2026-11-14T00:00:00Z");

    @Test
    void navigatesToResolvedCoordinateAcrossTicks() {
        List<String> resolved = new ArrayList<>();
        BlockPosition target = new BlockPosition(12, 64, -4);
        CoordinateResolver coordinates = coordinateId -> {
            resolved.add(coordinateId);
            return Optional.of(target);
        };
        FakeNavigation navigation = new FakeNavigation();
        NavigateAutomationModule module = new NavigateAutomationModule(
                coordinates,
                navigation,
                new MutableClock(START)
        );
        AutomationStep step = step(
                "travel",
                AutomationStepType.NAVIGATE,
                Map.of("coordinateId", "mine")
        );

        AutomationModuleResult running = execute(
                module,
                step,
                Map.of(),
                false
        );
        navigation.status = NavigationStatus.ARRIVED;
        AutomationModuleResult succeeded = execute(
                module,
                step,
                running.moduleState(),
                false
        );

        assertEquals(List.of("mine", "mine"), resolved);
        assertEquals(List.of(target), navigation.startedTargets);
        assertEquals(AutomationModuleStatus.RUNNING, running.status());
        assertEquals(AutomationModuleStatus.SUCCEEDED, succeeded.status());
        assertEquals(
                Optional.of("NAVIGATE:mine"),
                succeeded.lastConfirmedAction()
        );
    }

    @Test
    void reportsUnreachableNavigationAsFailure() {
        BlockPosition target = new BlockPosition(12, 64, -4);
        CoordinateResolver coordinates = coordinateId -> Optional.of(target);
        FakeNavigation navigation = new FakeNavigation();
        navigation.status = NavigationStatus.FAILED;
        NavigateAutomationModule module = new NavigateAutomationModule(
                coordinates,
                navigation,
                new MutableClock(START)
        );
        AutomationStep step = step(
                "travel",
                AutomationStepType.NAVIGATE,
                Map.of("coordinateId", "unreachable")
        );

        AutomationModuleResult failed = execute(
                module,
                step,
                Map.of(),
                false
        );

        assertEquals(AutomationModuleStatus.FAILED, failed.status());
        assertEquals(
                Optional.of("目标不可达或寻路失败: unreachable"),
                failed.message()
        );
    }

    @Test
    void waitUsesAutomationClockAndSupportsWebDurationAlias() {
        MutableClock clock = new MutableClock(START);
        WaitAutomationModule module = new WaitAutomationModule(clock);
        AutomationStep step = step(
                "wait",
                AutomationStepType.WAIT,
                Map.of("durationSeconds", "5")
        );

        AutomationModuleResult running = execute(
                module,
                step,
                Map.of(),
                false
        );
        clock.advance(Duration.ofSeconds(4));
        AutomationModuleResult stillRunning = execute(
                module,
                step,
                running.moduleState(),
                false
        );
        clock.advance(Duration.ofSeconds(1));
        AutomationModuleResult succeeded = execute(
                module,
                step,
                stillRunning.moduleState(),
                false
        );

        assertEquals(AutomationModuleStatus.RUNNING, stillRunning.status());
        assertEquals(AutomationModuleStatus.SUCCEEDED, succeeded.status());
        assertEquals(Optional.of("WAIT:5"), succeeded.lastConfirmedAction());
    }

    @Test
    void mapsEveryOperationModuleToItsPortRequest() {
        PickupRecordingPort pickup = new PickupRecordingPort();
        RunPickupAutomationModule pickupModule =
                new RunPickupAutomationModule(pickup);
        execute(pickupModule, step(
                "pickup",
                AutomationStepType.RUN_PICKUP,
                Map.of("workflowId", "quarry", "regionId", "north")
        ), Map.of(), false);
        assertEquals(
                new PickupWorkflowPort.Request("quarry", Optional.of("north")),
                pickup.requests.getFirst()
        );

        TransferRecordingPort transfers = new TransferRecordingPort();
        execute(new TakeItemsAutomationModule(transfers), step(
                "take",
                AutomationStepType.TAKE_ITEMS,
                Map.of("containerId", "bulk", "itemId", "minecraft:stone", "count", "4")
        ), Map.of(), false);
        execute(new PutItemsAutomationModule(transfers), step(
                "put",
                AutomationStepType.PUT_ITEMS,
                Map.of("containerId", "storage", "itemId", "minecraft:stone", "count", "3")
        ), Map.of(), false);
        execute(new TransferItemsAutomationModule(transfers), step(
                "transfer",
                AutomationStepType.TRANSFER_ITEMS,
                Map.of(
                "sourceContainerId", "bulk",
                "destination", "storage",
                "itemId", "minecraft:stone",
                "count", "2"
        )
        ), Map.of(), false);
        assertEquals(
                new ItemTransferPort.Request(
                ItemTransferPort.Mode.TAKE,
                "bulk",
                "PLAYER",
                "minecraft:stone",
                4
        ),
                transfers.requests.get(0)
        );
        assertEquals(
                new ItemTransferPort.Request(
                ItemTransferPort.Mode.PUT,
                "PLAYER",
                "storage",
                "minecraft:stone",
                3
        ),
                transfers.requests.get(1)
        );
        assertEquals(
                new ItemTransferPort.Request(
                ItemTransferPort.Mode.TRANSFER,
                "bulk",
                "storage",
                "minecraft:stone",
                2
        ),
                transfers.requests.get(2)
        );

        MaterialRecordingPort materials = new MaterialRecordingPort();
        execute(new AcquireMaterialsAutomationModule(materials), itemStep(
                "acquire",
                AutomationStepType.ACQUIRE_MATERIALS,
                "minecraft:iron_ingot",
                8
        ), Map.of(), false);
        assertEquals(
                new MaterialAcquisitionPort.Request("minecraft:iron_ingot", 8),
                materials.requests.getFirst()
        );

        CraftRecordingPort crafting = new CraftRecordingPort();
        execute(new CraftItemAutomationModule(crafting), itemStep(
                "craft",
                AutomationStepType.CRAFT_ITEM,
                "minecraft:pickaxe",
                1
        ), Map.of(), false);
        assertEquals(
                new CraftingPort.Request("minecraft:pickaxe", 1),
                crafting.requests.getFirst()
        );
    }

    @Test
    void operationModulesAdvanceFromRunningToConfirmedSuccess() {
        assertLifecycle(
                new RunPickupAutomationModule(new PickupRecordingPort()),
                step(
                "pickup",
                AutomationStepType.RUN_PICKUP,
                Map.of("workflowId", "quarry")
        )
        );
        assertLifecycle(
                new TakeItemsAutomationModule(new TransferRecordingPort()),
                itemContainerStep(
                "take",
                AutomationStepType.TAKE_ITEMS
        )
        );
        assertLifecycle(
                new PutItemsAutomationModule(new TransferRecordingPort()),
                itemContainerStep(
                "put",
                AutomationStepType.PUT_ITEMS
        )
        );
        assertLifecycle(
                new TransferItemsAutomationModule(new TransferRecordingPort()),
                step(
                "transfer",
                AutomationStepType.TRANSFER_ITEMS,
                Map.of(
                "sourceContainerId", "bulk",
                "destination", "storage",
                "itemId", "minecraft:stone",
                "count", "2"
        )
        )
        );
        assertLifecycle(
                new AcquireMaterialsAutomationModule(new MaterialRecordingPort()),
                itemStep(
                "acquire",
                AutomationStepType.ACQUIRE_MATERIALS,
                "minecraft:iron_ingot",
                8
        )
        );
        assertLifecycle(
                new CraftItemAutomationModule(new CraftRecordingPort()),
                itemStep(
                "craft",
                AutomationStepType.CRAFT_ITEM,
                "minecraft:pickaxe",
                1
        )
        );
    }

    @Test
    void recoveryReconcilesWithoutBeginningOperationAgain() {
        PickupRecordingPort pickup = new PickupRecordingPort();
        assertRecovery(
                new RunPickupAutomationModule(pickup),
                step(
                "pickup-recovery",
                AutomationStepType.RUN_PICKUP,
                Map.of("workflowId", "quarry")
        ),
                pickup
        );
        TransferRecordingPort take = new TransferRecordingPort();
        assertRecovery(
                new TakeItemsAutomationModule(take),
                itemContainerStep("take-recovery", AutomationStepType.TAKE_ITEMS),
                take
        );
        TransferRecordingPort put = new TransferRecordingPort();
        assertRecovery(
                new PutItemsAutomationModule(put),
                itemContainerStep("put-recovery", AutomationStepType.PUT_ITEMS),
                put
        );
        TransferRecordingPort transfer = new TransferRecordingPort();
        assertRecovery(
                new TransferItemsAutomationModule(transfer),
                step(
                "transfer-recovery",
                AutomationStepType.TRANSFER_ITEMS,
                Map.of(
                "sourceContainerId", "bulk",
                "destination", "storage",
                "itemId", "minecraft:stone",
                "count", "2"
        )
        ),
                transfer
        );
        MaterialRecordingPort acquire = new MaterialRecordingPort();
        assertRecovery(
                new AcquireMaterialsAutomationModule(acquire),
                itemStep(
                "acquire-recovery",
                AutomationStepType.ACQUIRE_MATERIALS,
                "minecraft:iron_ingot",
                8
        ),
                acquire
        );
        CraftRecordingPort craft = new CraftRecordingPort();
        assertRecovery(
                new CraftItemAutomationModule(craft),
                itemStep(
                "craft-recovery",
                AutomationStepType.CRAFT_ITEM,
                "minecraft:pickaxe",
                1
        ),
                craft
        );
    }

    @Test
    void inventoryChecksAuthoritativeCountsAndFailsWithDetail() {
        InventoryQueryPort insufficient = () -> new PlayerInventorySnapshot(
                36,
                List.of(new PlayerInventorySlot(0, "minecraft:stone", 3))
        );
        CheckInventoryAutomationModule module =
                new CheckInventoryAutomationModule(insufficient);
        AutomationModuleResult failed = execute(module, itemStep(
                "check",
                AutomationStepType.CHECK_INVENTORY,
                "minecraft:stone",
                5
        ), Map.of(), false);

        assertEquals(AutomationModuleStatus.FAILED, failed.status());
        assertTrue(failed.message().orElseThrow().contains("3"));
        assertTrue(failed.message().orElseThrow().contains("5"));

        InventoryQueryPort sufficient = () -> new PlayerInventorySnapshot(
                36,
                List.of(new PlayerInventorySlot(0, "minecraft:stone", 5))
        );
        AutomationModuleResult succeeded = execute(
                new CheckInventoryAutomationModule(sufficient),
                itemStep(
                "check",
                AutomationStepType.CHECK_INVENTORY,
                "minecraft:stone",
                5
        ),
                Map.of(),
                false
        );

        assertEquals(AutomationModuleStatus.SUCCEEDED, succeeded.status());
    }

    @Test
    void portPauseAndNavigationFailureArePropagated() {
        MaterialRecordingPort pausedPort = new MaterialRecordingPort();
        pausedPort.startResult = AutomationOperationResult.paused("需要腾出背包空间");
        AutomationModuleResult paused = execute(
                new AcquireMaterialsAutomationModule(pausedPort),
                itemStep(
                "acquire",
                AutomationStepType.ACQUIRE_MATERIALS,
                "minecraft:iron_ingot",
                8
        ),
                Map.of(),
                false
        );

        assertEquals(AutomationModuleStatus.PAUSED, paused.status());
        assertTrue(paused.message().orElseThrow().contains("背包"));

        NavigateAutomationModule navigation = new NavigateAutomationModule(
                coordinateId -> Optional.empty(),
                new FakeNavigation(),
                new MutableClock(START)
        );
        AutomationModuleResult failed = execute(
                navigation,
                step(
                "missing",
                AutomationStepType.NAVIGATE,
                Map.of("coordinateId", "missing")
        ),
                Map.of(),
                false
        );

        assertEquals(AutomationModuleStatus.FAILED, failed.status());
        assertTrue(failed.message().orElseThrow().contains("missing"));
    }

    @Test
    void factoryRegistersAllNineModulesWithChineseMetadata() {
        CoordinateResolver coordinates = coordinateId ->
                Optional.of(new BlockPosition(0, 64, 0));
        InventoryQueryPort inventory = () ->
                new PlayerInventorySnapshot(36, List.of());
        NavigationPort navigation = new FakeNavigation();
        AutomationClock clock = new MutableClock(START);
        PickupWorkflowPort pickups = new PickupRecordingPort();
        ItemTransferPort transfers = new TransferRecordingPort();
        MaterialAcquisitionPort materials = new MaterialRecordingPort();
        CraftingPort crafting = new CraftRecordingPort();
        DefaultAutomationModuleRegistryFactory factory = createFactory(
                coordinates,
                navigation,
                clock,
                pickups,
                transfers,
                inventory,
                materials,
                crafting
        );
        AutomationModuleRegistry registry = factory.create();

        for (AutomationStepType type : AutomationStepType.values()) {
            assertTrue(registry.contains(type), "missing module: " + type);
            AutomationModuleDescriptor descriptor =
                    registry.require(type).descriptor();
            assertEquals(type, descriptor.type());
            assertFalse(descriptor.displayName().isBlank());
            assertFalse(descriptor.description().isBlank());
            assertFalse(descriptor.parameters().isEmpty());
        }
    }

    @Test
    void operationKeyIsStableButSeparatedByTaskAndStep() {
        AutomationStep step = itemStep(
                "craft",
                AutomationStepType.CRAFT_ITEM,
                "minecraft:pickaxe",
                1
        );
        AutomationModule module = new CraftItemAutomationModule(new CraftRecordingPort());
        AutomationModuleResult first = executeNew(
                module,
                TASK_ID,
                step,
                Map.of()
        );
        AutomationModuleResult second = executeNew(
                module,
                TASK_ID,
                step,
                Map.of()
        );
        AutomationModuleResult otherTask = executeNew(
                module,
                UUID.randomUUID(),
                step,
                Map.of()
        );

        assertEquals(
                first.moduleState().get("operationKey"),
                second.moduleState().get("operationKey")
        );
        assertNotEquals(
                first.moduleState().get("operationKey"),
                otherTask.moduleState().get("operationKey")
        );
    }

    private static void assertLifecycle(
            AutomationModule module,
            AutomationStep step
    ) {
        AutomationModuleResult running = execute(
                module,
                step,
                Map.of(),
                false
        );
        AutomationModuleResult succeeded = execute(
                module,
                step,
                running.moduleState(),
                false
        );

        assertEquals(AutomationModuleStatus.RUNNING, running.status());
        assertTrue(running.lastConfirmedAction().isEmpty());
        assertEquals(AutomationModuleStatus.SUCCEEDED, succeeded.status());
        assertTrue(succeeded.lastConfirmedAction().isPresent());
    }

    private static void assertRecovery(
            AutomationModule module,
            AutomationStep step,
            RecordingPort<?> port
    ) {
        AutomationModuleResult started = execute(
                module,
                step,
                Map.of(),
                false
        );
        port.reconcileResult = AutomationOperationResult.succeeded(
                "confirmed:" + step.id()
        );
        AutomationModuleResult recovered = execute(
                module,
                step,
                started.moduleState(),
                true
        );

        assertEquals(1, port.startCount);
        assertEquals(1, port.reconcileCount);
        assertEquals(0, port.pollCount);
        assertEquals(AutomationModuleStatus.SUCCEEDED, recovered.status());
        assertEquals(
                Optional.of("confirmed:" + step.id()),
                recovered.lastConfirmedAction()
        );
    }

    private static AutomationModuleResult execute(
            AutomationModule module,
            AutomationStep step,
            Map<String, String> state,
            boolean recovered
    ) {
        return executeNew(module, TASK_ID, step, state, recovered);
    }

    private static AutomationModuleResult executeNew(
            AutomationModule module,
            UUID taskId,
            AutomationStep step,
            Map<String, String> state
    ) {
        return executeNew(module, taskId, step, state, false);
    }

    private static AutomationModuleResult executeNew(
            AutomationModule module,
            UUID taskId,
            AutomationStep step,
            Map<String, String> state,
            boolean recovered
    ) {
        return module.execute(new AutomationModuleContext(
                taskId,
                step,
                state,
                recovered,
                Optional.empty()
        ));
    }

    private static AutomationStep itemContainerStep(
            String id,
            AutomationStepType type
    ) {
        return step(
                id,
                type,
                Map.of(
                        "containerId", "bulk",
                        "itemId", "minecraft:stone",
                        "count", "4"
                )
        );
    }

    private static AutomationStep itemStep(
            String id,
            AutomationStepType type,
            String itemId,
            int count
    ) {
        return step(
                id,
                type,
                Map.of("itemId", itemId, "count", Integer.toString(count))
        );
    }

    private static AutomationStep step(
            String id,
            AutomationStepType type,
            Map<String, String> parameters
    ) {
        return new AutomationStep(id, type, parameters);
    }

    private static DefaultAutomationModuleRegistryFactory createFactory(
            CoordinateResolver coordinates,
            NavigationPort navigation,
            AutomationClock clock,
            PickupWorkflowPort pickups,
            ItemTransferPort transfers,
            InventoryQueryPort inventory,
            MaterialAcquisitionPort materials,
            CraftingPort crafting
    ) {
        return new DefaultAutomationModuleRegistryFactory(
                coordinates,
                navigation,
                clock,
                pickups,
                transfers,
                inventory,
                materials,
                crafting
        );
    }

    private static final class MutableClock implements AutomationClock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public Instant now() {
            return now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }
    }

    private static final class FakeNavigation implements NavigationPort {
        private NavigationStatus status = NavigationStatus.NAVIGATING;
        private final List<BlockPosition> startedTargets = new ArrayList<>();

        @Override
        public NavigationStatus start(BlockPosition target) {
            startedTargets.add(target);
            return status;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return status;
        }

        @Override
        public void cancel() {
            status = NavigationStatus.FAILED;
        }
    }

    private static class RecordingPort<Request>
            implements AutomationOperationPort<Request> {
        protected AutomationOperationResult startResult =
                AutomationOperationResult.running();
        private AutomationOperationResult pollResult =
                AutomationOperationResult.succeeded("confirmed");
        private AutomationOperationResult reconcileResult =
                AutomationOperationResult.running();
        protected final List<Request> requests = new ArrayList<>();
        private final List<String> keys = new ArrayList<>();
        private int startCount;
        private int pollCount;
        private int reconcileCount;

        @Override
        public AutomationOperationResult start(
                String operationKey,
                Request request
        ) {
            startCount++;
            keys.add(operationKey);
            requests.add(request);
            return startResult;
        }

        @Override
        public AutomationOperationResult poll(String operationKey) {
            pollCount++;
            keys.add(operationKey);
            return pollResult;
        }

        @Override
        public AutomationOperationResult reconcile(String operationKey) {
            reconcileCount++;
            keys.add(operationKey);
            return reconcileResult;
        }
    }

    private static final class PickupRecordingPort
            extends RecordingPort<PickupWorkflowPort.Request>
            implements PickupWorkflowPort {
    }

    private static final class TransferRecordingPort
            extends RecordingPort<ItemTransferPort.Request>
            implements ItemTransferPort {
    }

    private static final class MaterialRecordingPort
            extends RecordingPort<MaterialAcquisitionPort.Request>
            implements MaterialAcquisitionPort {
    }

    private static final class CraftRecordingPort
            extends RecordingPort<CraftingPort.Request>
            implements CraftingPort {
    }
}
