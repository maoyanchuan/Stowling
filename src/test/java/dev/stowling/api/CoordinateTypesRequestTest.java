package dev.stowling.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.control.ContainerPosition;
import dev.stowling.domain.CoordinateType;
import org.junit.jupiter.api.Test;

class CoordinateTypesRequestTest {
    private final StrictRequestParser parser = new StrictRequestParser();

    @Test
    void acceptsLegacyPointAndBothRadiusShapes() throws Exception {
        var point = parser.parseCoordinatePointsUpdate(body(""))
                .coordinatePoints().getFirst();
        assertEquals(CoordinateType.POINT, point.type());
        assertNull(point.radius());
        assertNull(point.secondCorner());

        for (CoordinateType type : new CoordinateType[] {
            CoordinateType.SPHERE, CoordinateType.CUBE
        }) {
            var range = parser.parseCoordinatePointsUpdate(body(
                    ",\"type\":\"" + type.name() + "\",\"radius\":8"
            )).coordinatePoints().getFirst();
            assertEquals(type, range.type());
            assertEquals(8, range.radius());
        }
    }

    @Test
    void acceptsReverseCornerAreaAndNullableUnusedFields() throws Exception {
        var area = parser.parseCoordinatePointsUpdate(body(
                ",\"type\":\"AREA\",\"radius\":null,"
                        + "\"secondCorner\":{\"x\":-10,\"y\":-60,\"z\":-8}"
        )).coordinatePoints().getFirst();
        assertEquals(CoordinateType.AREA, area.type());
        assertEquals(new ContainerPosition(-10, -60, -8), area.secondCorner());
        assertEquals(new ContainerPosition(1, -43, 4), area.position());

        var point = parser.parseCoordinatePointsUpdate(body(
                ",\"type\":\"POINT\",\"radius\":null,\"secondCorner\":null"
        )).coordinatePoints().getFirst();
        assertEquals(CoordinateType.POINT, point.type());
    }

    @Test
    void rejectsUnknownTypesInvalidGeometryAndMixedFields() {
        for (String extra : new String[] {
            ",\"type\":\"UNKNOWN\"",
            ",\"type\":null",
            ",\"type\":\"SPHERE\"",
            ",\"type\":\"CUBE\",\"radius\":0",
            ",\"type\":\"SPHERE\",\"radius\":129",
            ",\"type\":\"SPHERE\",\"radius\":1.5",
            ",\"type\":\"POINT\",\"radius\":8",
            ",\"type\":\"AREA\"",
            ",\"type\":\"AREA\",\"secondCorner\":{\"x\":1,\"y\":2}",
            ",\"type\":\"SPHERE\",\"radius\":8,"
                    + "\"secondCorner\":{\"x\":1,\"y\":2,\"z\":3}",
            ",\"type\":\"POINT\",\"unexpected\":true"
        }) {
            assertThrows(InvalidRequestException.class, () ->
                    parser.parseCoordinatePointsUpdate(body(extra)), extra);
        }
    }

    private static String body(String extra) {
        return """
                {
                  "revision":"revision-1",
                  "coordinatePoints":[{
                    "id":"pickup","name":"Pickup","dimension":"minecraft:overworld",
                    "position":{"x":1,"y":-43,"z":4}%s
                  }]
                }
                """.formatted(extra);
    }
}
