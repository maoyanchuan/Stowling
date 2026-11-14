package dev.stowling.automation.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationModuleStatus;
import dev.stowling.automation.AutomationOperationPort;
import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.PackingOperationPort;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.StorageRoute;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PackItemsAutomationModuleTest {
    private static final UUID TASK_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000303");
    private static final String DIMENSION = "minecraft:overworld";
    private static final String ITEM_ID = "minecraft:diamond";

    @Test
    void resolvesACompleteTaskPackingPlanFromNamedCoordinates() {
        RecordingPacking packing = new RecordingPacking();
        PackItemsAutomationModule module = new PackItemsAutomationModule(
                packing,
                PackItemsAutomationModuleTest::config
        );
        AutomationStep step = new AutomationStep(
                "pack",
                AutomationStepType.PACK_ITEMS,
                Map.of(
                        "sourceCoordinateId", "source",
                        "sourceContainerType", "BARREL",
                        "boxCoordinateId", "box",
                        "outputCoordinateId", "output",
                        "outputContainerType", "CHEST",
                        "fallbackCoordinateId", "fallback",
                        "fallbackContainerType", "TRAPPED_CHEST",
                        "itemId", ITEM_ID,
                        "count", "8",
                        "completionTrigger", "ON_OCCUPANCY",
                        "completionThreshold", "75"
                )
        );

        AutomationModuleResult result = module.execute(new AutomationModuleContext(
                TASK_ID,
                step,
                Map.of(),
                false,
                Optional.empty()
        ));

        assertEquals(AutomationModuleStatus.RUNNING, result.status());
        PackingOperationPort.Request request = packing.requests.getFirst();
        StorageRoute route = request.plan().route();
        PackingStation station = request.plan().stations().getFirst();
        assertEquals("source", route.bulkContainer().id());
        assertEquals(ContainerType.BARREL, route.bulkContainer().type());
        assertEquals(PackingTrigger.ON_OCCUPANCY, route.packingThresholdMode());
        assertEquals(75, route.packingThreshold());
        assertEquals(new BlockPosition(2, 64, 0), station.shulkerBoxPosition());
        assertEquals("output", station.productContainer().id());
        assertEquals(ContainerType.CHEST, station.productContainer().type());
        assertEquals("fallback", request.plan().overflowContainer().id());
        assertEquals(
                ContainerType.TRAPPED_CHEST,
                request.plan().overflowContainer().type()
        );
        assertEquals(ITEM_ID, request.items().getFirst().itemId());
        assertEquals(8, request.items().getFirst().count());
    }

    @Test
    void defaultContainerTypesAreChests() {
        RecordingPacking packing = new RecordingPacking();
        PackItemsAutomationModule module = new PackItemsAutomationModule(
                packing,
                PackItemsAutomationModuleTest::config
        );
        AutomationStep step = new AutomationStep(
                "pack-defaults",
                AutomationStepType.PACK_ITEMS,
                Map.of(
                        "sourceCoordinateId", "source",
                        "boxCoordinateId", "box",
                        "outputCoordinateId", "output",
                        "fallbackCoordinateId", "fallback",
                        "itemId", ITEM_ID,
                        "count", "1",
                        "completionTrigger", "ON_ITEM_COUNT",
                        "completionThreshold", "64"
                )
        );

        module.execute(new AutomationModuleContext(
                TASK_ID,
                step,
                Map.of(),
                false,
                Optional.empty()
        ));

        PackingOperationPort.Request request = packing.requests.getFirst();
        assertEquals(ContainerType.CHEST, request.source().type());
        assertEquals(
                ContainerType.CHEST,
                request.plan().stations().getFirst().productContainer().type()
        );
        assertEquals(
                ContainerType.CHEST,
                request.plan().overflowContainer().type()
        );
        assertTrue(request.plan().route().packingEnabled());
    }

    private static StowlingConfig config() {
        return new StowlingConfig(
                5,
                List.of(
                        coordinate("source", 1),
                        coordinate("box", 2),
                        coordinate("output", 3),
                        coordinate("fallback", 4)
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                container("overflow", 5)
        );
    }

    private static NamedCoordinate coordinate(String id, int x) {
        return new NamedCoordinate(
                id,
                id,
                DIMENSION,
                new BlockPosition(x, 64, 0)
        );
    }

    private static ContainerRef container(String id, int x) {
        return new ContainerRef(
                id,
                DIMENSION,
                new BlockPosition(x, 64, 0),
                ContainerType.CHEST
        );
    }

    private static final class RecordingPacking implements PackingOperationPort {
        private final List<Request> requests = new ArrayList<>();

        @Override
        public AutomationOperationResult start(String operationKey, Request request) {
            requests.add(request);
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult poll(String operationKey) {
            return AutomationOperationResult.running();
        }

        @Override
        public AutomationOperationResult reconcile(String operationKey) {
            return AutomationOperationResult.paused("manual recovery");
        }
    }
}
