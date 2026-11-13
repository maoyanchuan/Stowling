package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.PackingStation;
import dev.stowling.domain.PackingTrigger;
import dev.stowling.domain.StorageRoute;
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
}
