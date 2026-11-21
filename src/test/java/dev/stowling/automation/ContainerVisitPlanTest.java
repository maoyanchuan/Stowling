package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ContainerVisitPlanTest {
    @Test
    void skipsFullCachesButStillVisitsUnknownContainersAndMatchingPartialStacks() {
        var origin = new BlockPosition(0, 64, 0);
        var full = new ContainerTarget(origin.offset(1, 0, 0), "chest");
        var partial = new ContainerTarget(origin.offset(2, 0, 0), "chest");
        var unknown = new ContainerTarget(origin.offset(3, 0, 0), "chest");
        var snapshots = Map.of(
                full, List.of(new ShulkerTransferPlanner.Slot("minecraft:stone", 64, 64)),
                partial, List.of(new ShulkerTransferPlanner.Slot("minecraft:stone", 63, 64)));
        var route = new ContainerVisitPlan(List.of(full, unknown, partial));
        var incoming = Map.of("minecraft:stone", 64);
        var sizes = Map.of("minecraft:stone", 64);
        Predicate<ContainerTarget> eligible = cache ->
                !snapshots.containsKey(cache) || !ShulkerTransferPlanner.fittingInventory(
                        snapshots.get(cache), 1, incoming, sizes).isEmpty();

        assertEquals(partial, route.next(origin, eligible).orElseThrow());
        assertEquals(unknown, route.next(partial.position(), eligible).orElseThrow());
        assertTrue(route.next(origin, eligible).isEmpty());
    }

    @Test
    void aSkippedCacheCanBeSelectedAfterItsCapacityIsInvalidated() {
        var origin = new BlockPosition(0, 64, 0);
        var cache = new ContainerTarget(origin.offset(1, 0, 0), "chest");
        var route = new ContainerVisitPlan(List.of(cache));
        Set<ContainerTarget> full = new HashSet<>(Set.of(cache));

        assertTrue(route.next(origin, candidate -> !full.contains(candidate)).isEmpty());
        full.clear();
        assertEquals(cache, route.next(origin, candidate -> !full.contains(candidate))
                .orElseThrow());
        assertTrue(route.next(origin).isEmpty());
    }

    @Test
    void everySelectionUsesTheLatestPositionAmongEligibleCaches() {
        var origin = new BlockPosition(0, 64, 0);
        var blocked = new ContainerTarget(origin, "chest");
        var west = new ContainerTarget(origin.offset(-5, 0, 0), "chest");
        var east = new ContainerTarget(origin.offset(10, 0, 0), "chest");
        var route = new ContainerVisitPlan(List.of(blocked, west, east));

        assertEquals(east, route.next(origin.offset(9, 0, 0), cache -> !cache.equals(blocked))
                .orElseThrow());
        assertEquals(west, route.next(west.position(), cache -> !cache.equals(blocked))
                .orElseThrow());
        assertTrue(route.next(origin, cache -> !cache.equals(blocked)).isEmpty());
    }

    @Test
    void selectsFromLatestPositionWithoutRevisitingDuplicateContainers() {
        ContainerTarget first = new ContainerTarget(new BlockPosition(1, 64, 0), "chest");
        ContainerTarget duplicate = new ContainerTarget(first.position(), "container");
        ContainerTarget west = new ContainerTarget(new BlockPosition(-2, 64, 0), "chest");
        ContainerTarget east = new ContainerTarget(new BlockPosition(10, 64, 0), "chest");
        ContainerVisitPlan plan = new ContainerVisitPlan(List.of(west, first, duplicate, east));

        assertEquals(first, plan.next(new BlockPosition(0, 64, 0)).orElseThrow());
        assertEquals(east, plan.next(new BlockPosition(9, 64, 0)).orElseThrow());
        assertEquals(west, plan.next(east.position()).orElseThrow());
        assertTrue(plan.next(west.position()).isEmpty());
    }
}
