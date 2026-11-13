package dev.stowling.packing;

import static dev.stowling.packing.PackingTestFixtures.DIMENSION;
import static dev.stowling.packing.PackingTestFixtures.ITEM_ID;
import static dev.stowling.packing.PackingTestFixtures.OTHER_ITEM_ID;
import static dev.stowling.packing.PackingTestFixtures.available;
import static dev.stowling.packing.PackingTestFixtures.candidate;
import static dev.stowling.packing.PackingTestFixtures.workcell;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.PackingTrigger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PackingStationPoolTest {
    @Test
    void skipsUnreachableAndUnavailableStationsDeterministically() {
        PackingStationPool pool = new PackingStationPool();
        PackingWorkcell nearUnreachable = workcell(
                "near-unreachable",
                PackingTrigger.ON_ITEM_COUNT,
                64,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        PackingWorkcell offline = workcell(
                "offline",
                PackingTrigger.ON_ITEM_COUNT,
                64,
                PackingReleaseMode.AUTO_ON_CLOSE
        );
        List<PackingStationCandidate> candidates = List.of(
                candidate(nearUnreachable, false, true, 1),
                candidate(offline, true, false, 2),
                available("far", 30),
                available("near", 10)
        );

        PackingStationReservation reservation = pool.reserve(
                "task",
                ITEM_ID,
                DIMENSION,
                candidates
        ).orElseThrow();

        assertEquals("near", reservation.stationId());
    }

    @Test
    void breaksEqualDistanceTiesByStationId() {
        PackingStationPool pool = new PackingStationPool();

        PackingStationReservation reservation = pool.reserve(
                "task",
                ITEM_ID,
                DIMENSION,
                List.of(available("zone-b", 10), available("zone-a", 10))
        ).orElseThrow();

        assertEquals("zone-a", reservation.stationId());
    }

    @Test
    void prefersExistingItemAssignmentForPartialContinuation() {
        PackingStationPool pool = new PackingStationPool();
        PackingStationCandidate assignedFar = available("assigned-far", 100);
        PackingStationReservation first = pool.reserve(
                "task-one",
                ITEM_ID,
                DIMENSION,
                List.of(assignedFar)
        ).orElseThrow();
        assertTrue(pool.releaseKeepingAssignment(first));

        PackingStationReservation continuation = pool.reserve(
                "task-two",
                ITEM_ID,
                DIMENSION,
                List.of(available("empty-near", 1), assignedFar)
        ).orElseThrow();

        assertEquals("assigned-far", continuation.stationId());
    }

    @Test
    void preventsDifferentItemFromUsingAssignedPartialStation() {
        PackingStationPool pool = new PackingStationPool();
        PackingStationCandidate assigned = available("assigned", 1);
        PackingStationReservation first = pool.reserve(
                "task-one",
                ITEM_ID,
                DIMENSION,
                List.of(assigned)
        ).orElseThrow();
        pool.releaseKeepingAssignment(first);

        Optional<PackingStationReservation> otherItem = pool.reserve(
                "task-two",
                OTHER_ITEM_ID,
                DIMENSION,
                List.of(assigned)
        );

        assertTrue(otherItem.isEmpty());
        assertEquals(ITEM_ID, pool.assignedItemId("assigned").orElseThrow());
    }

    @Test
    void concurrentTasksCannotReserveOneStation() throws Exception {
        PackingStationPool pool = new PackingStationPool();
        List<PackingStationCandidate> candidates = List.of(available("only", 1));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<PackingStationReservation>> first = executor.submit(() -> {
                ready.countDown();
                start.await();
                return pool.reserve("task-one", ITEM_ID, DIMENSION, candidates);
            });
            Future<Optional<PackingStationReservation>> second = executor.submit(() -> {
                ready.countDown();
                start.await();
                return pool.reserve("task-two", ITEM_ID, DIMENSION, candidates);
            });
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();

            long reservations = List.of(first.get(), second.get()).stream()
                    .filter(Optional::isPresent)
                    .count();

            assertEquals(1, reservations);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleReleaseCannotFreeAReplacementReservation() {
        PackingStationPool pool = new PackingStationPool();
        List<PackingStationCandidate> candidates = List.of(available("station", 1));
        PackingStationReservation first = pool.reserve(
                "task-one",
                ITEM_ID,
                DIMENSION,
                candidates
        ).orElseThrow();
        pool.releaseKeepingAssignment(first);
        PackingStationReservation second = pool.reserve(
                "task-two",
                ITEM_ID,
                DIMENSION,
                candidates
        ).orElseThrow();

        assertFalse(pool.releaseAndClearAssignment(first));
        assertTrue(pool.isReserved("station"));
        assertTrue(pool.releaseKeepingAssignment(second));
    }
}
