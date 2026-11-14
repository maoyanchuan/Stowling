package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.CuboidPickupShape;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.SpherePickupShape;
import org.junit.jupiter.api.Test;

class MinecraftWorldObserverRegionTest {
    @Test
    void usesEntityCoordinatesAgainstSphereInsteadOfPlayerDistance() {
        PickupRegion region = new PickupRegion(
                "sphere",
                "Sphere",
                "minecraft:overworld",
                new BlockPosition(100, 64, 100),
                new SpherePickupShape(new BlockPosition(100, 64, 100), 4)
        );

        assertTrue(MinecraftWorldObserver.isInsidePickupRegion(
                region,
                103.5,
                64.0,
                100.0
        ));
        assertFalse(MinecraftWorldObserver.isInsidePickupRegion(
                region,
                104.1,
                64.0,
                100.0
        ));
    }

    @Test
    void includesCuboidBoundariesUsingPreciseEntityCoordinates() {
        PickupRegion region = new PickupRegion(
                "cuboid",
                "Cuboid",
                "minecraft:overworld",
                new BlockPosition(0, 64, 0),
                new CuboidPickupShape(
                        new BlockPosition(1, 2, 3),
                        new BlockPosition(4, 5, 6)
        )
        );

        assertTrue(MinecraftWorldObserver.isInsidePickupRegion(
                region,
                4.0,
                5.0,
                6.0
        ));
        assertFalse(MinecraftWorldObserver.isInsidePickupRegion(
                region,
                4.01,
                5.0,
                6.0
        ));
    }
}
