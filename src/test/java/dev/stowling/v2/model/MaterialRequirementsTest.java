package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialRequirementsTest {
    @Test
    void convertsWaterBucketsToOrdinaryIceAndSkipsLavaBuckets() {
        var parameters = Map.of(
                MaterialRequirements.ICE_INSTEAD_OF_WATER, "true",
                MaterialRequirements.PARAMETER, """
                        {"minecraft:ice":2,"minecraft:water_bucket":5,"minecraft:lava_bucket":3,
                        "minecraft:packed_ice":4,"minecraft:potion[water]":1}
                        """);
        var requested = MaterialRequirements.forCollection(parameters);

        assertEquals(Map.of("minecraft:ice", 7, "minecraft:packed_ice", 4,
                "minecraft:potion[water]", 1), requested);
        assertEquals(0, MaterialRequirements.reserveBoxes(new LinkedHashMap<>(requested),
                Map.of("minecraft:ice", 2, "minecraft:water_bucket", 1), 1));
    }

    @Test
    void keepsLegacyMaterialsUntilTheOptionIsEnabled() {
        var requested = MaterialRequirements.forCollection(Map.of(
                MaterialRequirements.PARAMETER,
                "{\"minecraft:water_bucket\":2,\"minecraft:lava_bucket\":1}"));

        assertEquals(Map.of("minecraft:water_bucket", 2, "minecraft:lava_bucket", 1), requested);
    }

    @Test
    void acceptsAnEmptyCollectionAfterSkippingLava() {
        var requested = MaterialRequirements.forCollection(Map.of(
                MaterialRequirements.PARAMETER, "{\"minecraft:lava_bucket\":1}",
                MaterialRequirements.ICE_INSTEAD_OF_WATER, "true"));

        assertEquals(Map.of(), requested);
    }

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
