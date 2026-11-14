package dev.stowling.automation.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationModule;
import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationModuleStatus;
import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.NamedCoordinate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskContainerModulesTest {
    private static final UUID TASK_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000202");
    private static final String DIMENSION = "minecraft:overworld";
    private static final String SOURCE_ID = "source";
    private static final String DESTINATION_ID = "destination";
    private static final String ITEM_ID = "minecraft:stone";
    private static final StowlingConfig CONFIG = config();

    @Test
    void takeAndPutResolveNamedContainersWithoutCacheResources() {
        RecordingTransfer transfers = new RecordingTransfer();
        TakeItemsAutomationModule take = new TakeItemsAutomationModule(
                transfers,
                () -> CONFIG
        );
        PutItemsAutomationModule put = new PutItemsAutomationModule(
                transfers,
                () -> CONFIG
        );

        AutomationModuleResult takeResult = execute(take, step(
                "take",
                AutomationStepType.TAKE_ITEMS,
                Map.of(
                        "coordinateId", SOURCE_ID,
                        "containerType", "BARREL",
                        "itemId", ITEM_ID,
                        "count", "4"
                )
        ));
        AutomationModuleResult putResult = execute(put, step(
                "put",
                AutomationStepType.PUT_ITEMS,
                Map.of(
                        "coordinateId", DESTINATION_ID,
                        "containerType", "CHEST",
                        "itemId", ITEM_ID,
                        "count", "3"
                )
        ));

        assertEquals(AutomationModuleStatus.RUNNING, takeResult.status());
        assertEquals(AutomationModuleStatus.RUNNING, putResult.status());
        assertEquals(2, transfers.requests.size());
        ItemTransferPort.Request takeRequest = transfers.requests.get(0);
        ItemTransferPort.Request putRequest = transfers.requests.get(1);
        assertEquals(
                Optional.of(container(SOURCE_ID, 1, ContainerType.BARREL)),
                takeRequest.sourceContainer()
        );
        assertTrue(takeRequest.destinationContainer().isEmpty());
        assertTrue(putRequest.sourceContainer().isEmpty());
        assertEquals(
                Optional.of(container(DESTINATION_ID, 2, ContainerType.CHEST)),
                putRequest.destinationContainer()
        );
    }

    @Test
    void transferResolvesBothNamedContainersInOneDimension() {
        RecordingTransfer transfers = new RecordingTransfer();
        TransferItemsAutomationModule module = new TransferItemsAutomationModule(
                transfers,
                () -> CONFIG
        );

        AutomationModuleResult result = execute(module, step(
                "transfer",
                AutomationStepType.TRANSFER_ITEMS,
                Map.of(
                        "sourceCoordinateId", SOURCE_ID,
                        "sourceContainerType", "CHEST",
                        "destinationCoordinateId", DESTINATION_ID,
                        "destinationContainerType", "BARREL",
                        "itemId", ITEM_ID,
                        "count", "2"
                )
        ));

        assertEquals(AutomationModuleStatus.RUNNING, result.status());
        ItemTransferPort.Request request = transfers.requests.getFirst();
        assertEquals(
                Optional.of(container(SOURCE_ID, 1, ContainerType.CHEST)),
                request.sourceContainer()
        );
        assertEquals(
                Optional.of(container(DESTINATION_ID, 2, ContainerType.BARREL)),
                request.destinationContainer()
        );
    }

    @Test
    void descriptorsAcceptLegacyContainerIdsBeforeExecution() {
        RecordingTransfer transfers = new RecordingTransfer();
        Map<String, String> legacy = Map.of(
                "containerId", "buffer", "itemId", ITEM_ID, "count", "2"
        );
        assertTrue(new TakeItemsAutomationModule(transfers)
                .descriptor().validate(legacy).isEmpty());
        assertTrue(new PutItemsAutomationModule(transfers)
                .descriptor().validate(legacy).isEmpty());
        assertTrue(new TransferItemsAutomationModule(transfers)
                .descriptor().validate(Map.of(
                        "sourceContainerId", "buffer",
                        "destination", "product",
                        "itemId", ITEM_ID,
                        "count", "2"
                )).isEmpty());
        assertTrue(new TakeItemsAutomationModule(transfers)
                .descriptor().validate(Map.of("itemId", ITEM_ID, "count", "2"))
                .stream().anyMatch(error -> error.contains("coordinateId")));
    }

    @Test
    void legacyConstructorStillUsesLegacyContainerIds() {
        RecordingTransfer transfers = new RecordingTransfer();
        TakeItemsAutomationModule module = new TakeItemsAutomationModule(transfers);

        AutomationModuleResult result = execute(module, step(
                "legacy-take",
                AutomationStepType.TAKE_ITEMS,
                Map.of(
                        "containerId", "cached-source",
                        "itemId", ITEM_ID,
                        "count", "1"
                )
        ));

        assertEquals(AutomationModuleStatus.RUNNING, result.status());
        ItemTransferPort.Request request = transfers.requests.getFirst();
        assertEquals("cached-source", request.source());
        assertTrue(request.sourceContainer().isEmpty());
    }

    private static AutomationModuleResult execute(
            AutomationModule module,
            AutomationStep step
    ) {
        return module.execute(new AutomationModuleContext(
                TASK_ID,
                step,
                Map.of(),
                false,
                Optional.empty()
        ));
    }

    private static AutomationStep step(
            String id,
            AutomationStepType type,
            Map<String, String> parameters
    ) {
        return new AutomationStep(id, type, parameters);
    }

    private static StowlingConfig config() {
        return new StowlingConfig(
                5,
                List.of(
                        coordinate(SOURCE_ID, 1),
                        coordinate(DESTINATION_ID, 2)
                ),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                container("overflow", 3, ContainerType.CHEST)
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

    private static ContainerRef container(
            String id,
            int x,
            ContainerType type
    ) {
        return new ContainerRef(
                id,
                DIMENSION,
                new BlockPosition(x, 64, 0),
                type
        );
    }

    private static final class RecordingTransfer implements ItemTransferPort {
        private final List<ItemTransferPort.Request> requests = new ArrayList<>();

        @Override
        public AutomationOperationResult start(
                String operationKey,
                ItemTransferPort.Request request
        ) {
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
