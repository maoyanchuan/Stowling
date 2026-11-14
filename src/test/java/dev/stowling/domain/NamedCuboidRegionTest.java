package dev.stowling.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.config.ConfigValidator;
import dev.stowling.config.StowlingConfig;
import java.util.List;
import org.junit.jupiter.api.Test;

class NamedCuboidRegionTest {
    private static final String DIMENSION = "minecraft:overworld";
    private static final BlockPosition MINIMUM = new BlockPosition(-12, 60, -8);
    private static final BlockPosition MAXIMUM = new BlockPosition(7, 80, 15);

    @Test
    void normalizesEachAxisIndependentlyForAllCornerOrderingsAndPreservesIds() {
        for (int reversedAxes = 0; reversedAxes < 8; reversedAxes++) {
            BlockPosition firstPosition = new BlockPosition(
                    (reversedAxes & 1) == 0 ? MINIMUM.x() : MAXIMUM.x(),
                    (reversedAxes & 2) == 0 ? MINIMUM.y() : MAXIMUM.y(),
                    (reversedAxes & 4) == 0 ? MINIMUM.z() : MAXIMUM.z()
            );
            BlockPosition secondPosition = new BlockPosition(
                    (reversedAxes & 1) == 0 ? MAXIMUM.x() : MINIMUM.x(),
                    (reversedAxes & 2) == 0 ? MAXIMUM.y() : MINIMUM.y(),
                    (reversedAxes & 4) == 0 ? MAXIMUM.z() : MINIMUM.z()
            );
            NamedCoordinate first = coordinate("first", firstPosition);
            NamedCoordinate second = coordinate("second", secondPosition);

            CuboidPickupShape shape = CuboidPickupShape.fromCoordinates(first, second);

            assertEquals("first", shape.firstCornerCoordinateId());
            assertEquals("second", shape.secondCornerCoordinateId());
            assertEquals(MINIMUM, shape.firstCorner(), "Axis mask: " + reversedAxes);
            assertEquals(MAXIMUM, shape.secondCorner(), "Axis mask: " + reversedAxes);
            assertEquals(PickupShapeType.CUBOID, shape.type());
            assertEquals(firstPosition, first.position());
            assertEquals(secondPosition, second.position());
        }
    }

    @Test
    void reversingArgumentsKeepsBoundsButPreservesTheNewReferenceOrder() {
        NamedCoordinate first = coordinate("high", MAXIMUM);
        NamedCoordinate second = coordinate("low", MINIMUM);

        CuboidPickupShape forward = CuboidPickupShape.fromCoordinates(first, second);
        CuboidPickupShape reverse = CuboidPickupShape.fromCoordinates(second, first);

        assertEquals(forward.firstCorner(), reverse.firstCorner());
        assertEquals(forward.secondCorner(), reverse.secondCorner());
        assertEquals("high", forward.firstCornerCoordinateId());
        assertEquals("low", forward.secondCornerCoordinateId());
        assertEquals("low", reverse.firstCornerCoordinateId());
        assertEquals("high", reverse.secondCornerCoordinateId());
    }

    @Test
    void namedRegionContainsBothOriginalCornersAndInclusiveNormalizedBoundaries() {
        NamedCoordinate first = coordinate("first", new BlockPosition(7, 60, 15));
        NamedCoordinate second = coordinate("second", new BlockPosition(-12, 80, -8));
        BlockPosition standby = new BlockPosition(100, 64, 100);
        PickupRegion region = new PickupRegion(
                "north", "North field", DIMENSION, "standby", standby,
                CuboidPickupShape.fromCoordinates(first, second)
        );

        assertTrue(region.contains(first.position()));
        assertTrue(region.contains(second.position()));
        assertTrue(region.contains(MINIMUM));
        assertTrue(region.contains(MAXIMUM));
        assertTrue(region.contains(0.5, 70.5, 1.5));
        assertFalse(region.contains(standby));
        assertFalse(region.contains(-12.01, 70, 0));
        assertFalse(region.contains(7.01, 70, 0));
        assertFalse(region.contains(0, 59.99, 0));
        assertFalse(region.contains(0, 80.01, 0));
        assertFalse(region.contains(0, 70, -8.01));
        assertFalse(region.contains(0, 70, 15.01));
        assertEquals("standby", region.standbyCoordinateId());
        assertEquals(standby, region.standbyPosition());
    }

    @Test
    void equalCornerPositionsRemainAValidSinglePointRegionWithDistinctIds() {
        BlockPosition point = new BlockPosition(3, 64, -2);
        CuboidPickupShape shape = CuboidPickupShape.fromCoordinates(
                coordinate("first", point), coordinate("second", point)
        );

        assertEquals("first", shape.firstCornerCoordinateId());
        assertEquals("second", shape.secondCornerCoordinateId());
        assertEquals(point, shape.firstCorner());
        assertEquals(point, shape.secondCorner());
        assertTrue(shape.contains(point));
        assertFalse(shape.contains(3.01, 64, -2));
    }

    @Test
    void directConstructorsContinueRejectingUnnormalizedCornersOnEveryAxis() {
        BlockPosition origin = new BlockPosition(0, 0, 0);
        for (BlockPosition first : List.of(
                new BlockPosition(1, 0, 0),
                new BlockPosition(0, 1, 0),
                new BlockPosition(0, 0, 1)
        )) {
            assertThrows(IllegalArgumentException.class, () ->
                    new CuboidPickupShape("first", "second", first, origin));
            assertThrows(IllegalArgumentException.class, () ->
                    new CuboidPickupShape(first, origin));
        }
    }

    @Test
    void legacyAndNamedDirectConstructorsStillAcceptNormalizedBounds() {
        CuboidPickupShape legacy = new CuboidPickupShape(MINIMUM, MAXIMUM);
        CuboidPickupShape named = new CuboidPickupShape("first", "second", MINIMUM, MAXIMUM);

        assertEquals(legacy.firstCorner(), named.firstCorner());
        assertEquals(legacy.secondCorner(), named.secondCorner());
        assertEquals("first", named.firstCornerCoordinateId());
        assertEquals("second", named.secondCornerCoordinateId());
        assertTrue(legacy.contains(MINIMUM));
        assertTrue(legacy.contains(MAXIMUM));
    }

    @Test
    void legacyConfigUpgradeAddsNamedCornerReferencesWithoutChangingTheRegion() {
        BlockPosition standby = new BlockPosition(100, 64, 100);
        PickupRegion legacy = new PickupRegion(
                "north", "North field", DIMENSION, standby,
                new CuboidPickupShape(MINIMUM, MAXIMUM)
        );
        StowlingConfig config = new StowlingConfig(
                2, List.of(legacy), List.of(), List.of(), List.of(), List.of(), List.of(),
                overflow()
        );
        PickupRegion upgraded = config.pickupRegions().getFirst();
        CuboidPickupShape shape = (CuboidPickupShape) upgraded.shape();

        assertEquals("north", upgraded.id());
        assertEquals("North field", upgraded.name());
        assertEquals(DIMENSION, upgraded.dimension());
        assertEquals(standby, namedCoordinate(config, upgraded.standbyCoordinateId()).position());
        assertEquals(MINIMUM, namedCoordinate(config, shape.firstCornerCoordinateId()).position());
        assertEquals(MAXIMUM,
                namedCoordinate(config, shape.secondCornerCoordinateId()).position());
        assertEquals(MINIMUM, shape.firstCorner());
        assertEquals(MAXIMUM, shape.secondCorner());
        assertTrue(upgraded.contains(MINIMUM));
        assertTrue(upgraded.contains(MAXIMUM));
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    @Test
    void currentConfigAcceptsNamedCornersWhoseOriginalAxesRunInOppositeDirections() {
        NamedCoordinate first = coordinate("first", new BlockPosition(7, 60, 15));
        NamedCoordinate second = coordinate("second", new BlockPosition(-12, 80, -8));
        NamedCoordinate standby = coordinate("standby", new BlockPosition(100, 64, 100));
        PickupRegion region = new PickupRegion(
                "north", "North field", DIMENSION, standby.id(), standby.position(),
                CuboidPickupShape.fromCoordinates(first, second)
        );
        StowlingConfig config = new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION,
                List.of(first, second, standby), List.of(region), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), overflow()
        );
        CuboidPickupShape shape = (CuboidPickupShape) config.pickupRegions().getFirst().shape();

        assertEquals("first", shape.firstCornerCoordinateId());
        assertEquals("second", shape.secondCornerCoordinateId());
        assertEquals(MINIMUM, shape.firstCorner());
        assertEquals(MAXIMUM, shape.secondCorner());
        assertTrue(ConfigValidator.validate(config).isEmpty());
    }

    private static NamedCoordinate coordinate(String id, BlockPosition position) {
        return new NamedCoordinate(id, id + " corner", DIMENSION, position);
    }

    private static NamedCoordinate namedCoordinate(StowlingConfig config, String id) {
        return config.coordinatePoints().stream()
                .filter(coordinate -> coordinate.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    private static ContainerRef overflow() {
        return new ContainerRef(
                "overflow", DIMENSION, new BlockPosition(200, 64, 200), ContainerType.CHEST
        );
    }
}
