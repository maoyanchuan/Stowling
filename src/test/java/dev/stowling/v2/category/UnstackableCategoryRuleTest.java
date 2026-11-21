package dev.stowling.v2.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class UnstackableCategoryRuleTest {
    @Test
    void recognizesDistinctRedstoneGroupsAndTheirVariants() {
        List<List<String>> groups = List.of(
                List.of("potion", "splash_potion#healing", "lingering_potion#poison"),
                List.of("oak_boat", "pale_oak_chest_boat", "bamboo_raft", "bamboo_chest_raft"),
                List.of("enchanted_book", "enchanted_book#mending"),
                List.of("music_disc_cat", "music_disc_bounce"),
                List.of("minecart", "chest_minecart", "hopper_minecart", "furnace_minecart"),
                List.of("leather_boots", "copper_chestplate", "turtle_helmet", "iron_leggings"),
                List.of("shears", "shears#damaged"),
                List.of("flint_and_steel", "flint_and_steel#damaged"),
                List.of("powder_snow_bucket"),
                List.of("lava_bucket"),
                List.of("water_bucket"),
                List.of("sulfur_cube_bucket", "sulfur_cube_bucket#absorbed_ice"));

        Set<String> categories = groups.stream().map(group -> {
            String category = family(group.getFirst());
            group.forEach(item -> assertEquals(category, family(item), item));
            return category;
        }).collect(Collectors.toSet());

        assertEquals(groups.size(), categories.size());
        assertTrue(!categories.contains(UnstackableCategoryRule.CATEGORY));
        for (String item : List.of("diamond_pickaxe", "elytra", "cod_bucket", "shulker_box")) {
            assertEquals(UnstackableCategoryRule.CATEGORY, family(item), item);
        }
        assertEquals(UnstackableCategoryRule.CATEGORY,
                UnstackableCategoryRule.familyCategory("example:oak_boat"));
    }

    @Test
    void differentSamplesInOneGroupLabelTheWholeStorageArea() {
        var targets = List.of(chest(4), chest(5));
        var markers = List.of(frame(0, "oak_boat"), frame(1, "bamboo_chest_raft"),
                frame(2, "diamond_pickaxe"));

        var result = UnstackableCategoryRule.resolveMarkerArea(markers, targets);

        assertTrue(result.succeeded());
        assertEquals(Map.of(family("pale_oak_chest_boat"), targets), result.categories());
    }

    @Test
    void unsupportedSampleDoesNotTurnItsStorageIntoACollectAllArea() {
        var targets = List.of(chest(0));
        var markers = List.of(frame(0, "diamond_pickaxe"));
        var rule = new UnstackableCategoryRule();

        assertTrue(rule.resolve(markers, targets).categories().isEmpty());
        assertTrue(UnstackableCategoryRule.resolveMarkerArea(markers, targets)
                .categories().isEmpty());
        assertEquals(Map.of(UnstackableCategoryRule.CATEGORY, targets),
                rule.resolve(List.of(), targets).categories());
    }

    private static CategoryMarker frame(int x, String item) {
        return new CategoryMarker(new BlockPosition(x, 64, 0), "minecraft:" + item,
                CategoryMarkerSource.ITEM_FRAME, -1);
    }

    private static ContainerTarget chest(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }

    private static String family(String item) {
        return UnstackableCategoryRule.familyCategory("minecraft:" + item);
    }
}
