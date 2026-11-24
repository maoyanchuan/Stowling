package dev.stowling.v2.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.RegionDefinition;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskTracePortsTest {
    @Test
    void interactionCallsIncludeTheActualStandingBoundsAndPickupPolicy() {
        TaskDiagnosticLog log = new TaskDiagnosticLog();
        var navigation = TaskTracePorts.wrap(NavigationPort.class, new Navigation());
        var target = new BlockPosition(5, -43, 32);
        var standing = new dev.stowling.v2.model.AreaBounds(4, -45, 34, 6, -45, 34);
        TaskTrace.within(log, UUID.randomUUID(), "TASK", "1", "PICKUP", () ->
                navigation.startInteraction(target, standing, false));

        var call = log.read(0, 10).events().getFirst();
        assertEquals("NavigationPort.startInteraction", call.operation());
        assertTrue(call.details().contains("minX=4"));
        assertTrue(call.details().contains("minY=-45"));
        assertTrue(call.details().contains("maxX=6"));
        assertTrue(call.details().contains("false"));
        var container = new dev.stowling.automation.ContainerTarget(target, "minecraft:chest",
                standing);
        assertTrue(TaskTraceValues.describe(container).contains("standingArea=AreaBounds"));
    }

    @Test
    void capacityProbesDoNotFloodTheLogBeforeTheCollectionBatchSummary() {
        TaskDiagnosticLog log = new TaskDiagnosticLog();
        DroppedItemObserverPort port = TaskTracePorts.wrap(DroppedItemObserverPort.class,
                new DroppedItemObserverPort() {
                    @Override
                    public List<DroppedItem> observe(BlockPosition center, int radius) {
                        return List.of();
                    }

                    @Override
                    public List<DroppedItem> observe(RegionDefinition region) {
                        return List.of();
                    }
                });
        DroppedItem drop = new DroppedItem(UUID.randomUUID(), "minecraft:stone", 64,
                new BlockPosition(1, 2, 3));
        TaskTrace.within(log, UUID.randomUUID(), "TASK", "1", "COLLECT", () -> {
            for (int item = 0; item < 1000; item++) assertTrue(port.canPickUp(drop));
            TaskTrace.event("DECISION", "pickup.capacity", "entities=1000; pickable=1000");
            return null;
        });

        assertEquals(1, log.read(0, 10).events().size());
        assertEquals("pickup.capacity", log.read(0, 10).events().getFirst().operation());
    }
    @Test
    void portTracingPreservesResultsAndRecordsCallsWithRunAndStepCorrelation() {
        TaskDiagnosticLog log = new TaskDiagnosticLog();
        NavigationPort port = TaskTracePorts.wrap(NavigationPort.class, new Navigation());
        assertSame(port, TaskTracePorts.wrap(NavigationPort.class, port));
        UUID owner = UUID.randomUUID();
        TaskTrace.within(log, owner, "TASK", "2.1", "MOVE", () -> {
            assertEquals(NavigationStatus.ARRIVED, port.start(new BlockPosition(1, 2, 3)));
            return null;
        });

        var events = log.read(0, 10).events();
        assertEquals(2, events.size());
        assertEquals("CALL", events.getFirst().kind());
        assertEquals("RETURN", events.getLast().kind());
        assertEquals(owner, events.getLast().runId());
        assertEquals("NavigationPort.start", events.getLast().operation());
        assertEquals("ARRIVED", events.getLast().details());
    }

    @Test
    void delegateFailuresKeepTheirIdentityAndDoNotLogArbitraryExceptionMessages() {
        TaskDiagnosticLog log = new TaskDiagnosticLog();
        RuntimeException failure = new IllegalStateException("Bearer private-token");
        NavigationPort port = TaskTracePorts.wrap(NavigationPort.class, new Navigation() {
            @Override
            public NavigationStatus start(BlockPosition target) {
                throw failure;
            }
        });
        RuntimeException caught = assertThrows(IllegalStateException.class, () ->
                TaskTrace.within(log, UUID.randomUUID(), "TASK", "1", "MOVE", () ->
                        port.start(new BlockPosition(1, 2, 3))));

        assertSame(failure, caught);
        assertEquals("IllegalStateException", log.read(0, 10).events().getLast().details());
        String redacted = TaskTraceValues.describe(Map.of("Authorization", "Bearer private-token",
                "customName", "private player name", "minecraft:stone", 64));
        assertTrue(redacted.contains("minecraft:stone"));
        assertTrue(!redacted.contains("private") && !redacted.contains("Bearer"));
    }

    private static class Navigation implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.ARRIVED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public void cancel() {
        }
    }
}
