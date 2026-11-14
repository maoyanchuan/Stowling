package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CachePolicy;
import dev.stowling.domain.ClassificationTiming;
import dev.stowling.domain.CompletionMode;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupRegionBinding;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.domain.SpherePickupShape;
import dev.stowling.domain.StorageRoute;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {
    @Test
    void rejectsEqualPriorityRouteAmbiguity() {
        StorageRoute first = ConfigTestFixtures.route(
                "first",
                List.of("minecraft:cobblestone"),
                10
        );
        StorageRoute second = ConfigTestFixtures.route(
                "second",
                List.of("minecraft:cobblestone"),
                10
        );
        StowlingConfig config = ConfigTestFixtures.config(List.of(first, second));

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(config)
        );

        assertTrue(exception.getMessage().contains("ambiguous selector"));
    }

    @Test
    void allowsPriorityToResolveRouteOverlap() {
        StorageRoute fallback = ConfigTestFixtures.route(
                "fallback",
                List.of("minecraft:cobblestone"),
                1
        );
        StorageRoute specific = ConfigTestFixtures.route(
                "specific",
                List.of("minecraft:cobblestone"),
                10
        );
        StowlingConfig config = ConfigTestFixtures.config(List.of(fallback, specific));

        assertDoesNotThrow(
                () -> ConfigValidator.requireValid(config)
        );
    }

    @Test
    void rejectsUnknownPackingStation() {
        ContainerRef bulk = ConfigTestFixtures.container("bulk", 20);
        StorageRoute route = new StorageRoute(
                "pack-route",
                List.of("minecraft:diamond"),
                List.of(),
                10,
                bulk,
                64,
                "missing-station"
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(ConfigTestFixtures.config(List.of(route)))
        );

        assertTrue(exception.getMessage().contains("packing station does not exist"));
    }

    @Test
    void acceptsStationWhoseProductContainerMatchesDimension() {
        ContainerRef product = ConfigTestFixtures.container("product", 30);
        PackingStation station = new PackingStation(
                "station",
                "minecraft:overworld",
                new BlockPosition(12, 64, 0),
                product,
                PackingTrigger.ON_ITEM_COUNT,
                64
        );
        StorageRoute route = new StorageRoute(
                "pack-route",
                List.of("minecraft:diamond"),
                List.of(),
                10,
                ConfigTestFixtures.container("bulk", 20),
                64,
                station.id()
        );
        StowlingConfig base = ConfigTestFixtures.config(List.of(route));
        StowlingConfig config = new StowlingConfig(
                base.schemaVersion(),
                base.defaultIdleDelay(),
                base.pickupSites(),
                base.storageRoutes(),
                List.of(station),
                base.auditSchedules(),
                base.overflowContainer()
        );

        List<ConfigViolation> violations = ConfigValidator.validate(config);

        assertEquals(List.of(), violations);
    }

    @Test
    void allowsCachesToShareNamedCoordinates() {
        PickupRegion region = region("farm", "minecraft:overworld");
        NamedCache first = cache("cache-one", "minecraft:overworld", 8);
        NamedCache duplicate = cache("cache-two", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("farm"),
                List.of("cache-one")
        );
        StowlingConfig valid = config(
                List.of(region),
                List.of(first, duplicate),
                List.of(workflow)
        );

        assertDoesNotThrow(() -> ConfigValidator.requireValid(valid));
        assertEquals(
                valid.cacheContainers().getFirst().coordinateId(),
                valid.cacheContainers().getLast().coordinateId()
        );
    }

    @Test
    void rejectsCrossDimensionWorkflowReferences() {
        PickupRegion region = region("nether-farm", "minecraft:the_nether");
        NamedCache cache = cache("overworld-cache", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "mixed-patrol",
                "minecraft:overworld",
                List.of("nether-farm"),
                List.of("overworld-cache")
        );
        StowlingConfig invalid = config(
                List.of(region),
                List.of(cache),
                List.of(workflow)
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("workflow dimension"));
    }

    @Test
    void rejectsDuplicateIdsAcrossPickupResources() {
        PickupRegion region = region("shared-id", "minecraft:overworld");
        NamedCache cache = cache("shared-id", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("shared-id"),
                List.of("shared-id")
        );
        StowlingConfig invalid = config(
                List.of(region),
                List.of(cache),
                List.of(workflow)
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("duplicate id"));
    }

    @Test
    void rejectsEmptyWorkflowAndWorkflowWithoutCaches() {
        PickupWorkflow workflow = workflow(
                "empty-patrol",
                "minecraft:overworld",
                List.of(),
                List.of()
        );
        StowlingConfig invalid = config(List.of(), List.of(), List.of(workflow));

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("workflow is empty"));
        assertTrue(exception.getMessage().contains("at least one cache"));
    }

    @Test
    void rejectsUnknownAndUnsafeReferences() {
        PickupRegion region = region("farm", "minecraft:overworld");
        NamedCache cache = cache("cache", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("missing/region"),
                List.of("missing-cache")
        );
        StowlingConfig invalid = config(
                List.of(region),
                List.of(cache),
                List.of(workflow)
        );

        ConfigValidationException exception = assertThrows(
                ConfigValidationException.class,
                () -> ConfigValidator.requireValid(invalid)
        );

        assertTrue(exception.getMessage().contains("pickup region does not exist"));
        assertTrue(exception.getMessage().contains("cache does not exist"));
        assertTrue(exception.getMessage().contains("id must match"));
    }

    @Test
    void allowsIndependentStandbyPositionOutsideRegion() {
        PickupRegion region = new PickupRegion(
                "farm",
                "Farm",
                "minecraft:overworld",
                new BlockPosition(20, 64, 0),
                new SpherePickupShape(new BlockPosition(0, 64, 0), 8)
        );
        NamedCache cache = cache("cache", "minecraft:overworld", 8);
        PickupWorkflow workflow = workflow(
                "patrol",
                "minecraft:overworld",
                List.of("farm"),
                List.of("cache")
        );
        StowlingConfig valid = config(
                List.of(region),
                List.of(cache),
                List.of(workflow)
        );

        assertDoesNotThrow(() -> ConfigValidator.requireValid(valid));
    }

    private static PickupRegion region(String id, String dimension) {
        BlockPosition center = new BlockPosition(0, 64, 0);
        return new PickupRegion(
                id,
                id,
                dimension,
                center,
                new SpherePickupShape(center, 16)
        );
    }

    private static NamedCache cache(String id, String dimension, int x) {
        return new NamedCache(
                id,
                id,
                dimension,
                new BlockPosition(x, 64, 0),
                ContainerType.CHEST
        );
    }

    private static PickupWorkflow workflow(
            String id,
            String dimension,
            List<String> regionIds,
            List<String> cacheIds
    ) {
        List<PickupRegionBinding> bindings = regionIds.stream()
                .map(ConfigValidatorTest::binding)
                .toList();
        return new PickupWorkflow(
                id,
                id,
                dimension,
                bindings,
                cacheIds,
                CachePolicy.NEAREST_AVAILABLE,
                ClassificationTiming.AFTER_ROUND,
                CompletionMode.ALL_IDLE,
                Duration.ofSeconds(300)
        );
    }

    private static PickupRegionBinding binding(String regionId) {
        return new PickupRegionBinding(regionId, 100, Duration.ofSeconds(60));
    }

    private static StowlingConfig config(
            List<PickupRegion> regions,
            List<NamedCache> caches,
            List<PickupWorkflow> workflows
    ) {
        StowlingConfig base = ConfigTestFixtures.config(List.of());
        return new StowlingConfig(
                2,
                regions,
                caches,
                workflows,
                base.storageRoutes(),
                base.packingStations(),
                List.of(),
                base.overflowContainer()
        );
    }
}
