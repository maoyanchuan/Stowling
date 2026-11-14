package dev.stowling.domain;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PickupShapeTest {
    @Test
    void sphereUsesInclusiveThreeDimensionalBoundary() {
        SpherePickupShape shape = new SpherePickupShape(
                new BlockPosition(10, 64, -5),
                5
        );

        assertTrue(shape.contains(new BlockPosition(13, 68, -5)));
        assertTrue(shape.contains(new BlockPosition(15, 64, -5)));
        assertTrue(shape.contains(14.9, 64.0, -5.0));
        assertFalse(shape.contains(new BlockPosition(15, 65, -5)));
        assertFalse(shape.contains(15.0, 64.1, -5.0));
    }

    @Test
    void cuboidIncludesBothCornersAndRejectsReversedBounds() {
        CuboidPickupShape shape = new CuboidPickupShape(
                new BlockPosition(-2, 60, -4),
                new BlockPosition(2, 70, 4)
        );

        assertTrue(shape.contains(new BlockPosition(-2, 60, -4)));
        assertTrue(shape.contains(new BlockPosition(2, 70, 4)));
        assertTrue(shape.contains(1.9, 69.5, 3.9));
        assertFalse(shape.contains(new BlockPosition(3, 70, 4)));
        assertFalse(shape.contains(2.1, 70.0, 4.0));
        BlockPosition reversedFirst = new BlockPosition(2, 60, -4);
        BlockPosition reversedSecond = new BlockPosition(-2, 70, 4);
        assertThrows(
                IllegalArgumentException.class,
                () -> new CuboidPickupShape(reversedFirst, reversedSecond)
        );
    }

    @Test
    void legacyConstructorsLeaveCoordinateReferencesForConfigUpgrade() {
        BlockPosition position = new BlockPosition(1, 64, 2);
        PickupRegion region = new PickupRegion(
                "legacy-region",
                "Legacy region",
                "minecraft:overworld",
                position,
                new SpherePickupShape(position, 8)
        );
        NamedCache cache = new NamedCache(
                "legacy-cache",
                "Legacy cache",
                "minecraft:overworld",
                position,
                ContainerType.CHEST
        );

        assertNull(region.standbyCoordinateId());
        assertNull(((SpherePickupShape) region.shape()).centerCoordinateId());
        assertNull(cache.coordinateId());
    }
}
