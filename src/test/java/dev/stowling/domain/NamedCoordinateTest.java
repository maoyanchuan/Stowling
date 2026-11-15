package dev.stowling.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NamedCoordinateTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void resolvesSphereAndCubeFromCoordinateDefinition() {
        NamedCoordinate sphere = new NamedCoordinate(
                "sphere",
                "Sphere",
                DIMENSION,
                new BlockPosition(4, 64, -3),
                CoordinateType.SPHERE,
                3,
                null
        );
        NamedCoordinate cube = new NamedCoordinate(
                "cube",
                "Cube",
                DIMENSION,
                new BlockPosition(4, 64, -3),
                CoordinateType.CUBE,
                2,
                null
        );

        SpherePickupShape sphereShape = (SpherePickupShape) sphere.pickupShape();
        CuboidPickupShape cubeShape = (CuboidPickupShape) cube.pickupShape();

        assertTrue(sphereShape.contains(7, 64, -3));
        assertFalse(sphereShape.contains(8, 64, -3));
        assertEquals(new BlockPosition(2, 62, -5), cubeShape.firstCorner());
        assertEquals(new BlockPosition(6, 66, -1), cubeShape.secondCorner());
    }

    @Test
    void resolvesReverseAreaCornersToNormalizedBounds() {
        NamedCoordinate area = new NamedCoordinate(
                "area",
                "Area",
                DIMENSION,
                new BlockPosition(10, 80, -4),
                CoordinateType.AREA,
                null,
                new BlockPosition(-2, 60, 8)
        );

        CuboidPickupShape shape = (CuboidPickupShape) area.pickupShape();

        assertEquals(new BlockPosition(-2, 60, -4), shape.firstCorner());
        assertEquals(new BlockPosition(10, 80, 8), shape.secondCorner());
        assertTrue(shape.contains(0, 70, 0));
        assertEquals(new BlockPosition(10, 80, -4), area.position());
        assertEquals(new BlockPosition(-2, 60, 8), area.secondCorner());
        assertEquals(new BlockPosition(4, 70, 2), area.anchorPosition());
    }

    @Test
    void fourArgumentCoordinateRemainsPointOnly() {
        NamedCoordinate point = new NamedCoordinate(
                "point",
                "Point",
                DIMENSION,
                new BlockPosition(1, 2, 3)
        );

        assertEquals(CoordinateType.POINT, point.type());
        assertThrows(IllegalArgumentException.class, point::pickupShape);
    }

    @Test
    void rejectsFieldsThatDoNotMatchCoordinateType() {
        BlockPosition position = new BlockPosition(0, 64, 0);
        BlockPosition corner = new BlockPosition(4, 68, 4);

        assertThrows(IllegalArgumentException.class, () ->
                new NamedCoordinate(
                        "point",
                        "Point",
                        DIMENSION,
                        position,
                        CoordinateType.POINT,
                        8,
                        null
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new NamedCoordinate(
                        "sphere",
                        "Sphere",
                        DIMENSION,
                        position,
                        CoordinateType.SPHERE,
                        null,
                        null
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new NamedCoordinate(
                        "cube",
                        "Cube",
                        DIMENSION,
                        position,
                        CoordinateType.CUBE,
                        129,
                        null
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new NamedCoordinate(
                        "area",
                        "Area",
                        DIMENSION,
                        position,
                        CoordinateType.AREA,
                        null,
                        null
                )
        );
        assertThrows(IllegalArgumentException.class, () ->
                new NamedCoordinate(
                        "area-radius",
                        "Area radius",
                        DIMENSION,
                        position,
                        CoordinateType.AREA,
                        4,
                        corner
                )
        );
    }
}
