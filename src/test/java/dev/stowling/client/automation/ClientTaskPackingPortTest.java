package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationOperationStatus;
import dev.stowling.automation.ItemAmount;
import dev.stowling.automation.PackingDisposition;
import dev.stowling.automation.PackingOperationPort;
import dev.stowling.automation.PackingPort;
import dev.stowling.automation.PackingRequest;
import dev.stowling.automation.TaskPackingPlan;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.StorageRoute;
import dev.stowling.schedule.StopOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ClientTaskPackingPortTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final String ITEM_ID = "minecraft:diamond";
    private static final UUID PARENT_TASK =
            UUID.fromString("00000000-0000-0000-0000-000000000404");

    @Test
    void waitsForAcceptedDispositionAndUsesIndependentPackingTaskIds() {
        RecordingPacking packing = new RecordingPacking();
        ClientTaskPackingPort port = new ClientTaskPackingPort(
                packing,
                new ImmediateScheduler()
        );

        AutomationOperationResult first = port.start(
                PARENT_TASK + ":pack-one",
                request()
        );
        AutomationOperationResult second = port.start(
                PARENT_TASK + ":pack-two",
                request()
        );

        assertEquals(AutomationOperationStatus.RUNNING, first.status());
        assertEquals(AutomationOperationStatus.RUNNING, second.status());
        assertEquals(2, packing.submitted.size());
        assertNotEquals(
                packing.submitted.get(0).taskId(),
                packing.submitted.get(1).taskId()
        );
        assertNotEquals(
                PARENT_TASK,
                packing.submitted.get(0).taskId()
        );

        packing.pollDisposition = PackingDisposition.ACCEPTED;
        AutomationOperationResult accepted = port.poll(
                PARENT_TASK + ":pack-one"
        );

        assertEquals(AutomationOperationStatus.SUCCEEDED, accepted.status());
        assertEquals(1, packing.polled.size());
    }

    @Test
    void stopIsPendingThenCleanOnlyAfterPackingConfirms() {
        RecordingPacking packing = new RecordingPacking();
        packing.stopOutcome = StopOutcome.PENDING;
        packing.pollStopOutcome = StopOutcome.CLEAN;
        ClientTaskPackingPort port = new ClientTaskPackingPort(
                packing,
                new ImmediateScheduler()
        );
        String key = PARENT_TASK + ":pack";
        port.start(key, request());

        assertEquals(StopOutcome.PENDING, port.requestStop(PARENT_TASK));
        assertEquals(
                packing.submitted.getFirst().taskId(),
                packing.requestStops.getFirst()
        );
        assertEquals(StopOutcome.CLEAN, port.pollStop(PARENT_TASK));
        assertEquals(StopOutcome.CLEAN, port.pollStop(PARENT_TASK));
        assertEquals(1, packing.pollStopCalls);
    }

    @Test
    void unsafeStopIsReportedWithoutReplayingPacking() {
        RecordingPacking packing = new RecordingPacking();
        packing.stopOutcome = StopOutcome.UNSAFE;
        ClientTaskPackingPort port = new ClientTaskPackingPort(
                packing,
                new ImmediateScheduler()
        );
        port.start(PARENT_TASK + ":pack", request());

        assertEquals(StopOutcome.UNSAFE, port.requestStop(PARENT_TASK));
        assertEquals(1, packing.submitted.size());
        assertEquals(0, packing.polled.size());
    }

    @Test
    void missingStatePausesWithoutReplayingThePackingRequest() {
        RecordingPacking packing = new RecordingPacking();
        ClientTaskPackingPort port = new ClientTaskPackingPort(
                packing,
                new ImmediateScheduler()
        );

        AutomationOperationResult result = port.reconcile(
                PARENT_TASK + ":missing"
        );

        assertEquals(AutomationOperationStatus.PAUSED, result.status());
        assertEquals(0, packing.submitted.size());
        assertEquals(0, packing.polled.size());
    }

    private static PackingOperationPort.Request request() {
        ContainerRef source = container("source", 1);
        ContainerRef output = container("output", 2);
        ContainerRef overflow = container("overflow", 3);
        String stationId = "task-box";
        StorageRoute route = new StorageRoute(
                "task-source",
                List.of(ITEM_ID),
                List.of(),
                0,
                source,
                PackingTrigger.ON_ITEM_COUNT,
                64,
                List.of(stationId)
        );
        PackingStation station = new PackingStation(
                stationId,
                DIMENSION,
                new BlockPosition(4, 64, 0),
                output,
                PackingTrigger.ON_ITEM_COUNT,
                64
        );
        TaskPackingPlan plan = new TaskPackingPlan(
                route,
                List.of(station),
                overflow
        );
        return new PackingOperationPort.Request(
                plan,
                source,
                List.of(new ItemAmount(ITEM_ID, 8))
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

    private static final class RecordingPacking implements PackingPort {
        private final List<PackingRequest> submitted = new ArrayList<>();
        private final List<PackingRequest> polled = new ArrayList<>();
        private final List<UUID> requestStops = new ArrayList<>();
        private PackingDisposition startDisposition = PackingDisposition.PENDING;
        private PackingDisposition pollDisposition = PackingDisposition.PENDING;
        private StopOutcome stopOutcome = StopOutcome.UNSAFE;
        private StopOutcome pollStopOutcome = StopOutcome.UNSAFE;
        private int pollStopCalls;

        @Override
        public PackingDisposition submit(PackingRequest request) {
            submitted.add(request);
            return startDisposition;
        }

        @Override
        public PackingDisposition poll(PackingRequest request) {
            polled.add(request);
            return pollDisposition;
        }

        @Override
        public StopOutcome requestStop(UUID taskId) {
            requestStops.add(taskId);
            return stopOutcome;
        }

        @Override
        public StopOutcome pollStop(UUID taskId) {
            pollStopCalls++;
            return pollStopOutcome;
        }
    }

    private static final class ImmediateScheduler implements ClientActionScheduler {
        @Override
        public boolean isClientThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> action) {
            return CompletableFuture.completedFuture(action.get());
        }
    }
}
