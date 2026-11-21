package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialRequirementsTest {
    @Test
    void reservesSeveralMatchingBoxesWithoutExceedingAnyMaterialCount() {
        var remaining = new LinkedHashMap<>(Map.of("minecraft:stone", 140, "minecraft:glass", 10));
        var contents = Map.of("minecraft:stone", 64, "minecraft:glass", 3);

        assertEquals(2, MaterialRequirements.reserveBoxes(remaining, contents, 3));
        assertEquals(Map.of("minecraft:stone", 12, "minecraft:glass", 4), remaining);
        assertEquals(0, MaterialRequirements.reserveBoxes(remaining, contents, 3));
        assertEquals(Map.of("minecraft:stone", 12, "minecraft:glass", 4), remaining);
    }

    @Test
    void leavesMixedBoxesWithUnrequestedContentsInTheWarehouse() {
        var remaining = new LinkedHashMap<>(Map.of("minecraft:stone", 64));
        var contents = Map.of("minecraft:stone", 64, "minecraft:dirt", 8);

        assertEquals(0, MaterialRequirements.reserveBoxes(remaining, contents, 1));
        assertEquals(Map.of("minecraft:stone", 64), remaining);
    }
}
