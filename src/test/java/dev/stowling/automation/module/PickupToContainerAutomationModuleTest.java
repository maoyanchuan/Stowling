package dev.stowling.automation.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationModuleStatus;
import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.InventoryQueryPort;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.PickupWorkflowPort;
import dev.stowling.automation.PlayerInventorySlot;
import dev.stowling.automation.PlayerInventorySnapshot;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.CoordinateType;
import dev.stowling.domain.NamedCoordinate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PickupToContainerAutomationModuleTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final UUID TASK_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000701");
    private static final ContainerRef OVERFLOW = new ContainerRef(
            "overflow",
            DIMENSION,
            new BlockPosition(0, 64, 0),
            ContainerType.CHEST
    );

    @Test
    void depositsOnlyItemsCollectedDuringThePickupRound() {
        Inventory inventory = new Inventory(
                snapshot(Map.of("minecraft:apple", 2)),
                snapshot(Map.of(
                        "minecraft:apple", 2,
                        "minecraft:dirt", 4,
                        "minecraft:diamond", 1
                ))
        );
        Pickup pickup = new Pickup();
        Transfer transfer = new Transfer(AutomationOperationStatus.SUCCEEDED);
        Navigation navigation = new Navigation();
        PickupToContainerAutomationModule module = module(
                pickup,
                transfer,
                inventory,
                navigation
        );

        AutomationModuleResult result = run(module, "detect");

        assertEquals(AutomationModuleStatus.SUCCEEDED, result.status());
        assertEquals(1, pickup.requests.size());
        assertEquals(
                "drops",
                pickup.requests.getFirst().customRules().orElseThrow()
                        .selectedCoordinateId()
        );
        assertEquals(false, pickup.requests.getFirst().waitForItems());
        assertEquals(2, transfer.requests.size());
        assertTrue(transfer.requests.stream()
                .noneMatch(request -> request.itemId().equals("minecraft:apple")));
        assertEquals(
                List.of("minecraft:diamond", "minecraft:dirt"),
                transfer.requests.stream().map(ItemTransferPort.Request::itemId).toList()
        );
        assertEquals("chest", transfer.requests.getFirst().destination());
        assertEquals(
                "chest",
                transfer.requests.getFirst().destinationContainer()
                        .orElseThrow().id()
        );
        assertEquals(
                ContainerType.CHEST,
                transfer.requests.getFirst().destinationContainer()
                        .orElseThrow().type()
        );
        assertEquals(List.of(1, 4), transfer.requests.stream()
                .map(ItemTransferPort.Request::count)
                .toList());
        assertEquals(1, navigation.starts);
    }

    @Test
    void completesWithoutNavigationWhenTheRangeIsEmpty() {
        Inventory inventory = new Inventory(
                snapshot(Map.of("minecraft:apple", 2)),
                snapshot(Map.of("minecraft:apple", 2))
        );
        Pickup pickup = new Pickup();
        Transfer transfer = new Transfer(AutomationOperationStatus.SUCCEEDED);
        Navigation navigation = new Navigation();
        PickupToContainerAutomationModule module = module(
                pickup,
                transfer,
                inventory,
                navigation
        );

        AutomationModuleResult result = run(module, "empty");

        assertEquals(AutomationModuleStatus.SUCCEEDED, result.status());
        assertTrue(transfer.requests.isEmpty());
        assertEquals(0, navigation.starts);
    }

    @Test
    void pausesWhenTheDestinationCannotAcceptTheCollectedItem() {
        Inventory inventory = new Inventory(
                snapshot(Map.of()),
                snapshot(Map.of("minecraft:dirt", 4))
        );
        Pickup pickup = new Pickup();
        Transfer transfer = new Transfer(AutomationOperationStatus.PAUSED);
        Navigation navigation = new Navigation();
        PickupToContainerAutomationModule module = module(
                pickup,
                transfer,
                inventory,
                navigation
        );

        AutomationModuleResult result = run(module, "full");

        assertEquals(AutomationModuleStatus.PAUSED, result.status());
        assertEquals(1, transfer.requests.size());
        assertEquals("完整容器", result.message().orElseThrow());
    }

    private static PickupToContainerAutomationModule module(
            Pickup pickup,
            Transfer transfer,
            Inventory inventory,
            Navigation navigation
    ) {
        StowlingConfig config = new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                List.of(
                        new NamedCoordinate(
                                "drops",
                                "Drops",
                                DIMENSION,
                                new BlockPosition(10, 64, 10),
                                CoordinateType.SPHERE,
                                8,
                                null
                        ),
                        new NamedCoordinate(
                                "chest",
                                "Chest",
                                DIMENSION,
                                new BlockPosition(30, 65, 10)
                        )
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                OVERFLOW
        );
        return new PickupToContainerAutomationModule(
                pickup,
                transfer,
                inventory,
                navigation,
                () -> config
        );
    }

    private static AutomationModuleResult run(
            PickupToContainerAutomationModule module,
            String stepId
    ) {
        AutomationStep step = new AutomationStep(
                stepId,
                AutomationStepType.PICKUP_TO_CONTAINER,
                Map.of(
                        "coordinateId", "drops",
                        "destinationCoordinateId", "chest",
                        "containerType", "CHEST",
                        "idleSeconds", "2",
                        "waitForItems", "false"
                )
        );
        Map<String, String> state = Map.of();
        AutomationModuleResult result = AutomationModuleResult.running(state);
        for (int tick = 0; tick < 12
                && result.status() == AutomationModuleStatus.RUNNING; tick++) {
            result = module.execute(new AutomationModuleContext(
                    TASK_ID,
                    step,
                    state,
                    false,
                    Optional.empty()
            ));
            state = result.moduleState();
        }
        return result;
    }

    private static PlayerInventorySnapshot snapshot(Map<String, Integer> counts) {
        List<PlayerInventorySlot> slots = new ArrayList<>();
        int slot = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            slots.add(new PlayerInventorySlot(slot++, entry.getKey(), entry.getValue()));
        }
        return new PlayerInventorySnapshot(36, slots);
    }

    private static final class Inventory implements InventoryQueryPort {
        private final List<PlayerInventorySnapshot> snapshots;
        private int index;

        private Inventory(
                PlayerInventorySnapshot baseline,
                PlayerInventorySnapshot afterPickup
        ) {
            snapshots = List.of(baseline, afterPickup);
        }

        @Override
        public PlayerInventorySnapshot snapshot() {
            PlayerInventorySnapshot result = snapshots.get(
                    Math.min(index++, snapshots.size() - 1)
            );
            return result;
        }
    }

    private static final class Pickup implements PickupWorkflowPort {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public AutomationOperationResult start(String operationKey, Request request) {
            requests.add(request);
            return AutomationOperationResult.succeeded("pickup complete");
        }

        @Override
        public AutomationOperationResult poll(String operationKey) {
            return AutomationOperationResult.succeeded("pickup complete");
        }

        @Override
        public AutomationOperationResult reconcile(String operationKey) {
            return AutomationOperationResult.succeeded("pickup complete");
        }
    }

    private static final class Transfer implements ItemTransferPort {
        private final AutomationOperationStatus status;
        private final List<Request> requests = new ArrayList<>();

        private Transfer(AutomationOperationStatus status) {
            this.status = status;
        }

        @Override
        public AutomationOperationResult start(String operationKey, Request request) {
            requests.add(request);
            return switch (status) {
                case SUCCEEDED -> AutomationOperationResult.succeeded("deposited");
                case PAUSED -> AutomationOperationResult.paused("完整容器");
                case FAILED -> AutomationOperationResult.failed("failed");
                case RUNNING -> AutomationOperationResult.running();
            };
        }

        @Override
        public AutomationOperationResult poll(String operationKey) {
            return start(operationKey, requests.getLast());
        }

        @Override
        public AutomationOperationResult reconcile(String operationKey) {
            return poll(operationKey);
        }
    }

    private static final class Navigation implements NavigationPort {
        private int starts;

        @Override
        public NavigationStatus start(BlockPosition target) {
            starts++;
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public void cancel() {
        }
    }
}
