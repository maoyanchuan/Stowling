package dev.stowling.routing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.StorageRoute;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RouteMatcherTest {
    @Test
    void exactItemRouteWinsOverHigherPriorityTagRoute() {
        StorageRoute exact = route(
                "exact",
                List.of("minecraft:oak_log"),
                List.of(),
                1
        );
        StorageRoute tag = route(
                "tag",
                List.of(),
                List.of("minecraft:logs"),
                99
        );
        RouteMatcher matcher = new RouteMatcher(List.of(tag, exact));

        RouteResolution result = matcher.resolve(
                "minecraft:oak_log",
                Set.of("minecraft:logs")
        );

        assertEquals(RouteResolutionStatus.MATCHED, result.status());
        assertEquals("exact", result.match().orElseThrow().route().id());
        assertEquals(RouteSelectorKind.ITEM_ID, result.match().orElseThrow().selectorKind());
    }

    @Test
    void prioritySelectsOneOfMultipleTagRoutes() {
        StorageRoute fallback = route(
                "fallback",
                List.of(),
                List.of("minecraft:logs"),
                1
        );
        StorageRoute preferred = route(
                "preferred",
                List.of(),
                List.of("minecraft:logs"),
                10
        );
        RouteMatcher matcher = new RouteMatcher(List.of(fallback, preferred));

        RouteResolution result = matcher.resolve(
                "minecraft:birch_log",
                Set.of("minecraft:logs")
        );

        assertEquals("preferred", result.match().orElseThrow().route().id());
    }

    @Test
    void equalTopPriorityExactRoutesReportDeterministicAmbiguity() {
        RouteMatcher matcher = new RouteMatcher(List.of(
                route("z-route", List.of("minecraft:stone"), List.of(), 5),
                route("a-route", List.of("minecraft:stone"), List.of(), 5)
        ));

        RouteResolution result = matcher.resolve("minecraft:stone", Set.of());

        assertEquals(RouteResolutionStatus.AMBIGUOUS, result.status());
        assertEquals(List.of("a-route", "z-route"), result.candidateRouteIds());
        assertTrue(result.match().isEmpty());
    }

    @Test
    void equalTopPriorityTagsRemainAmbiguousAfterExactRoutesAreAbsent() {
        RouteMatcher matcher = new RouteMatcher(List.of(
                route("second", List.of(), List.of("minecraft:logs"), 8),
                route("first", List.of(), List.of("minecraft:logs"), 8)
        ));

        RouteResolution result = matcher.resolve(
                "minecraft:spruce_log",
                Set.of("minecraft:logs")
        );

        assertEquals(RouteResolutionStatus.AMBIGUOUS, result.status());
        assertEquals(List.of("first", "second"), result.candidateRouteIds());
    }

    @Test
    void returnsNoMatchForUnroutedItems() {
        RouteMatcher matcher = new RouteMatcher(List.of(
                route("stones", List.of(), List.of("minecraft:stone"), 1)
        ));

        RouteResolution result = matcher.resolve("minecraft:dirt", Set.of("minecraft:dirt"));

        assertEquals(RouteResolutionStatus.NO_MATCH, result.status());
    }

    @Test
    void supportsOccupiedSlotRatioThreshold() {
        PackingThreshold threshold = new PackingThreshold(PackingTrigger.ON_OCCUPANCY, 75);

        assertTrue(threshold.reached(27, 36, 0));
        assertTrue(!threshold.reached(26, 36, 10_000));
    }

    @Test
    void supportsItemCountThreshold() {
        PackingThreshold threshold = new PackingThreshold(PackingTrigger.ON_ITEM_COUNT, 1728);

        assertTrue(threshold.reached(10, 36, 1728));
        assertTrue(!threshold.reached(36, 36, 1727));
    }

    @Test
    void rejectsInvalidThresholdValues() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new PackingThreshold(PackingTrigger.ON_OCCUPANCY, 101)
        );
        assertThrows(
                IllegalArgumentException.class,
                () -> new PackingThreshold(PackingTrigger.ON_ITEM_COUNT, 0)
        );
    }

    private static StorageRoute route(
            String id,
            List<String> itemIds,
            List<String> itemTags,
            int priority
    ) {
        return new StorageRoute(
                id,
                itemIds,
                itemTags,
                priority,
                new ContainerRef(
                        "bulk-" + id,
                        "minecraft:overworld",
                        new BlockPosition(0, 64, 0),
                        ContainerType.CHEST
                ),
                null,
                null
        );
    }
}
