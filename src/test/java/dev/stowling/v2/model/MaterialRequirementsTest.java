package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class MaterialRequirementsTest {
    @Test
    void collectsAllBoxColorsAndMinecartVariantsByDefaultForExistingTasks() {
        var materials = unstackableMaterials();

        assertEquals(materials, MaterialRequirements.forCollection(Map.of(
                MaterialRequirements.PARAMETER, new Gson().toJson(materials))));
    }

    @Test
    void disablesBoxAndMinecartCollectionIndependentlyWithoutRemovingOtherMaterials() {
        var materials = unstackableMaterials();
        String original = new Gson().toJson(materials);
        for (String disabled : List.of(MaterialRequirements.COLLECT_SHULKER_BOXES,
                MaterialRequirements.COLLECT_MINECARTS)) {
            var expected = new LinkedHashMap<>(materials);
            String suffix = disabled.equals(MaterialRequirements.COLLECT_SHULKER_BOXES)
                    ? "shulker_box" : "minecart";
            expected.keySet().removeIf(id -> id.startsWith("minecraft:") && id.endsWith(suffix));
            var requested = MaterialRequirements.forCollection(Map.of(
                    MaterialRequirements.PARAMETER, original, disabled, "false"));

            assertEquals(expected, requested);
            assertThrows(UnsupportedOperationException.class,
                    () -> requested.put("minecraft:dirt", 1));
            assertEquals(materials, MaterialRequirements.parse(original));
        }
    }

    @Test
    void combinesCollectionSwitchesWithLiquidRulesWithoutChangingPackingSettings() {
        var materials = unstackableMaterials();
        materials.put("minecraft:water_bucket", 2);
        materials.put("minecraft:lava_bucket", 1);
        materials.put("minecraft:ice", 3);

        var requested = MaterialRequirements.forCollection(Map.of(
                MaterialRequirements.PARAMETER, new Gson().toJson(materials),
                MaterialRequirements.COLLECT_SHULKER_BOXES, "false",
                MaterialRequirements.COLLECT_MINECARTS, "false",
                MaterialRequirements.ICE_INSTEAD_OF_WATER, "true",
                MaterialRequirements.SKIP_LAVA, "true",
                MaterialRequirements.BOXED_DELIVERY, "true"));

        assertEquals(Map.of("minecraft:stone", 64, "example:red_shulker_box", 1,
                "example:chest_minecart", 1, "minecraft:ice", 5), requested);
    }

    @Test
    void acceptsAnEmptyCollectionAfterDisablingBothMaterialGroups() {
        assertEquals(Map.of(), MaterialRequirements.forCollection(Map.of(
                MaterialRequirements.PARAMETER,
                "{\"minecraft:shulker_box\":1,\"minecraft:minecart\":2}",
                MaterialRequirements.COLLECT_SHULKER_BOXES, "false",
                MaterialRequirements.COLLECT_MINECARTS, "false")));
    }

    private static Map<String, Integer> unstackableMaterials() {
        var materials = new LinkedHashMap<>(Map.of("minecraft:stone", 64,
                "minecraft:shulker_box", 1, "example:red_shulker_box", 1,
                "example:chest_minecart", 1));
        for (String color : List.of("white", "orange", "magenta", "light_blue", "yellow",
                "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown",
                "green", "red", "black")) {
            materials.put("minecraft:" + color + "_shulker_box", 1);
        }
        for (String cart : List.of("minecart", "chest_minecart", "hopper_minecart",
                "furnace_minecart", "tnt_minecart", "command_block_minecart")) {
            materials.put("minecraft:" + cart, 2);
        }
        return materials;
    }

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
                MaterialRequirements.SKIP_LAVA, "true"));

        assertEquals(Map.of(), requested);
    }

    @Test
    void makesTheLavaOptionIndependentOfBothWaterOptions() {
        String original = "{\"minecraft:water_bucket\":2,\"minecraft:lava_bucket\":1}";
        assertEquals(Map.of("minecraft:ice", 2, "minecraft:lava_bucket", 1),
                MaterialRequirements.forCollection(Map.of(
                        MaterialRequirements.PARAMETER, original,
                        MaterialRequirements.ICE_INSTEAD_OF_WATER, "true",
                        MaterialRequirements.SKIP_LAVA, "false")));
        assertEquals(Map.of("minecraft:lava_bucket", 1),
                MaterialRequirements.forCollection(Map.of(
                        MaterialRequirements.PARAMETER, original,
                        MaterialRequirements.SKIP_WATER_BUCKETS, "true")));
        assertEquals(Map.of("minecraft:water_bucket", 2),
                MaterialRequirements.forCollection(Map.of(
                        MaterialRequirements.PARAMETER, original,
                        MaterialRequirements.SKIP_LAVA, "true")));
    }

    @Test
    void rejectsBothWaterOptionsEnabled() {
        assertThrows(IllegalArgumentException.class, () -> MaterialRequirements.forCollection(
                Map.of(MaterialRequirements.PARAMETER, "{\"minecraft:water_bucket\":2}",
                        MaterialRequirements.SKIP_WATER_BUCKETS, "true",
                        MaterialRequirements.ICE_INSTEAD_OF_WATER, "true")));
    }

    @Test
    void deliversAStockBoxIntactOnlyWhenItPreservesTheMaterialGroup() {
        String stone = "minecraft:stone";
        var remaining = Map.of(stone, 100);
        assertFalse(MaterialRequirements.canDeliverBox(stone, remaining,
                Map.of(stone, 35), false, false));
        assertFalse(MaterialRequirements.canDeliverBox(stone, remaining,
                Map.of(stone, 100), false, true));
        assertFalse(MaterialRequirements.canDeliverBox(stone, remaining,
                Map.of(stone, 100, "minecraft:glass", 1), false, false));
        assertTrue(MaterialRequirements.canDeliverBox(stone, remaining,
                Map.of(stone, 100), false, false));
        assertTrue(MaterialRequirements.canDeliverBox(stone, Map.of(stone, 2000),
                Map.of(stone, 1728), true, true));
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
