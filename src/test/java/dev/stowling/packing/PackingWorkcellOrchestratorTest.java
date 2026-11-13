package dev.stowling.packing;

import static dev.stowling.packing.PackingTestFixtures.ITEM_ID;
import static dev.stowling.packing.PackingTestFixtures.OTHER_ITEM_ID;
import static dev.stowling.packing.PackingTestFixtures.available;
import static dev.stowling.packing.PackingTestFixtures.candidate;
import static dev.stowling.packing.PackingTestFixtures.request;
import static dev.stowling.packing.PackingTestFixtures.workcell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.PackingTrigger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PackingWorkcellOrchestratorTest {
    @Test
    void autoOnCloseCreatesTransferToRouteBulkContainer() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell station = workcell(
                "station",
                PackingTrigger.ON_ITEM_COUNT,
                4,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();
        PackingRequest packingRequest = request(4);

        PackingRunResult result = new PackingWorkcellOrchestrator(pool).pack(
                packingRequest,
                List.of(candidate(station, true, true, 1)),
                port
        );

        assertEquals(PackingRunStatus.FULL_BOX_READY, result.status());
        assertEquals(4, result.insertedQuantity());
        assertTrue(result.transferIntent().isPresent());
        assertEquals(
                station.station().productContainer(),
                result.transferIntent().orElseThrow().source()
        );
        assertEquals(
                packingRequest.route().bulkContainer(),
                result.transferIntent().orElseThrow().destination()
        );
        assertEquals(PackingTestFixtures.autoOnCloseCalls(), port.calls());
        assertFalse(pool.assignedItemId("station").isPresent());
    }

    @Test
    void explicitProtocolClosesThenInteractsWithBlock() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell station = workcell(
                "station",
                PackingTrigger.ON_ITEM_COUNT,
                4,
                PackingReleaseMode.EXPLICIT_INTERACT_BLOCK
        );
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();

        PackingRunResult result = new PackingWorkcellOrchestrator(pool).pack(
                request(4),
                List.of(candidate(station, true, true, 1)),
                port
        );

        assertEquals(PackingRunStatus.FULL_BOX_READY, result.status());
        assertEquals(PackingTestFixtures.explicitInteractCalls(), port.calls());
    }

    @Test
    void partialBoxStaysAssignedAndCanContinueLater() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell station = workcell(
                "station",
                PackingTrigger.ON_ITEM_COUNT,
                64,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();
        PackingWorkcellOrchestrator orchestrator = new PackingWorkcellOrchestrator(pool);
        List<PackingStationCandidate> candidates = List.of(candidate(station, true, true, 1));

        PackingRunResult partial = orchestrator.pack(request(16), candidates, port);

        assertEquals(PackingRunStatus.PARTIAL_BOX, partial.status());
        assertEquals(16, partial.partialBoxes().getFirst().contents().exactItemCount(ITEM_ID));
        assertEquals(ITEM_ID, pool.assignedItemId("station").orElseThrow());
        assertFalse(pool.isReserved("station"));

        PackingRunResult completed = orchestrator.pack(request(48), candidates, port);

        assertEquals(PackingRunStatus.FULL_BOX_READY, completed.status());
        assertEquals(48, completed.insertedQuantity());
        assertEquals("station", completed.transferIntent().orElseThrow().stationId());
    }

    @Test
    void timeoutMovesToNextReachableStation() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell first = workcell(
                "first",
                PackingTrigger.ON_ITEM_COUNT,
                4,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingWorkcell second = workcell(
                "second",
                PackingTrigger.ON_ITEM_COUNT,
                4,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();
        port.failInspection("first");

        PackingRunResult result = new PackingWorkcellOrchestrator(pool).pack(
                request(4),
                List.of(candidate(first, true, true, 1), candidate(second, true, true, 5)),
                port
        );

        assertEquals(PackingRunStatus.FULL_BOX_READY, result.status());
        assertEquals("second", result.transferIntent().orElseThrow().stationId());
        assertEquals("first", result.stationFailures().getFirst().stationId());
        assertEquals(
                PackingFailureStage.INSPECTION,
                result.stationFailures().getFirst().stage()
        );
        assertTrue(result.stationFailures().getFirst().reason().startsWith("timeout:"));
    }

    @Test
    void allStationFailuresProduceOverflowForUnplacedItems() {
        PackingStationPool pool = new PackingStationPool();
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();
        port.failInspection("first");
        port.failInspection("second");

        PackingRunResult result = new PackingWorkcellOrchestrator(pool).pack(
                request(9),
                List.of(available("first", 1), available("second", 2)),
                port
        );

        assertEquals(PackingRunStatus.OVERFLOW, result.status());
        assertEquals(0, result.insertedQuantity());
        assertEquals(9, result.overflowIntent().orElseThrow().quantity());
        assertEquals(2, result.stationFailures().size());
    }

    @Test
    void failedFullProtocolRetainsInsertedItemsInsteadOfOverflowingThem() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell station = workcell(
                "station",
                PackingTrigger.ON_ITEM_COUNT,
                4,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();
        port.failClose("station");

        PackingRunResult result = new PackingWorkcellOrchestrator(pool).pack(
                request(4),
                List.of(candidate(station, true, true, 1)),
                port
        );

        assertEquals(PackingRunStatus.FAILED_RETAINED, result.status());
        assertEquals(4, result.insertedQuantity());
        assertTrue(result.overflowIntent().isEmpty());
        assertEquals(PackingFailureStage.CLOSE, result.stationFailures().getFirst().stage());
        assertEquals(ITEM_ID, pool.assignedItemId("station").orElseThrow());
    }

    @Test
    void partialInsertionOnFailedPathOverflowsOnlyTheRemainder() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell first = workcell(
                "first",
                PackingTrigger.ON_ITEM_COUNT,
                64,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingWorkcell second = workcell(
                "second",
                PackingTrigger.ON_ITEM_COUNT,
                64,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();
        port.limitInsertion("first", 6);
        port.failInspection("second");

        PackingRunResult result = new PackingWorkcellOrchestrator(pool).pack(
                request(16),
                List.of(candidate(first, true, true, 1), candidate(second, true, true, 2)),
                port
        );

        assertEquals(PackingRunStatus.OVERFLOW, result.status());
        assertEquals(6, result.insertedQuantity());
        assertEquals(10, result.overflowIntent().orElseThrow().quantity());
        assertEquals(6, result.partialBoxes().getFirst().contents().exactItemCount(ITEM_ID));
        assertEquals(ITEM_ID, pool.assignedItemId("first").orElseThrow());
    }

    @Test
    void mixedItemShulkerIsRejectedAndFailsOver() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell mixed = workcell(
                "mixed",
                PackingTrigger.ON_ITEM_COUNT,
                4,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingWorkcell clean = workcell(
                "clean",
                PackingTrigger.ON_ITEM_COUNT,
                4,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();
        PackingBoxContents mixedContents = new PackingBoxContents(
                Map.of(ITEM_ID, 1, OTHER_ITEM_ID, 2),
                2,
                27
        );
        port.setContents("mixed", mixedContents);

        PackingRunResult result = new PackingWorkcellOrchestrator(pool).pack(
                request(4),
                List.of(candidate(mixed, true, true, 1), candidate(clean, true, true, 2)),
                port
        );

        assertEquals(PackingRunStatus.FULL_BOX_READY, result.status());
        assertEquals("clean", result.transferIntent().orElseThrow().stationId());
        assertTrue(result.stationFailures().getFirst().reason().contains("more than one"));
        assertEquals(ITEM_ID, pool.assignedItemId("mixed").orElseThrow());
    }

    @Test
    void unconfirmedServerSlotChangeForcesFailover() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell first = workcell(
                "first",
                PackingTrigger.ON_ITEM_COUNT,
                4,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingWorkcell second = workcell(
                "second",
                PackingTrigger.ON_ITEM_COUNT,
                4,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();
        port.doNotConfirmInsertion("first");

        PackingRunResult result = new PackingWorkcellOrchestrator(pool).pack(
                request(4),
                List.of(candidate(first, true, true, 1), candidate(second, true, true, 2)),
                port
        );

        assertEquals("second", result.transferIntent().orElseThrow().stationId());
        assertTrue(
                result.stationFailures().getFirst().reason().contains("did not confirm")
        );
    }

    @Test
    void occupancyThresholdCanCompleteAFullBox() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell station = workcell(
                "station",
                PackingTrigger.ON_OCCUPANCY,
                50,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingTestFixtures.FakePackingPort port = new PackingTestFixtures.FakePackingPort();
        port.setCapacitySlots(2);

        PackingRunResult result = new PackingWorkcellOrchestrator(pool).pack(
                request(65),
                List.of(candidate(station, true, true, 1)),
                port
        );

        assertEquals(PackingRunStatus.FULL_BOX_READY, result.status());
        assertEquals(65, result.insertedQuantity());
        assertFalse(port.calls().contains("trigger:station"));
    }
}
