package dev.stowling.v2.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirectBlockIdCategoryRuleTest {
    @Test
    void mapsEachMarkerBlockToTheContainerInItsColumn() {
        DirectBlockIdCategoryRule rule =
                new DirectBlockIdCategoryRule();
        ContainerTarget logChest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        ContainerTarget plankChest = new ContainerTarget(
                new BlockPosition(8, -40, 1),
                "minecraft:chest"
        );

        CategoryRuleResult result = rule.resolve(
                List.of(
                        new CategoryMarker(
                                new BlockPosition(7, -40, 2),
                                "minecraft:oak_log"
                        ),
                        new CategoryMarker(
                                new BlockPosition(8, -40, 2),
                                "minecraft:oak_planks"
                        )
                ),
                List.of(logChest, plankChest)
        );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(logChest),
                result.categories().get("minecraft:oak_log")
        );
        assertEquals(
                List.of(plankChest),
                result.categories().get("minecraft:oak_planks")
        );
        assertTrue(rule.matchesItem(
                "minecraft:oak_planks",
                "minecraft:oak_planks"
        ));
        assertFalse(rule.matchesItem(
                "minecraft:oak_planks",
                "minecraft:oak_log"
        ));
    }

    @Test
    void mergesEquivalentMarkersFromDifferentDisplayMethods() {
        DirectBlockIdCategoryRule rule =
                new DirectBlockIdCategoryRule();
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        BlockPosition column = new BlockPosition(7, -40, 2);

        CategoryRuleResult result = rule.resolve(
                List.of(
                        new CategoryMarker(
                                column,
                                "minecraft:torch",
                                CategoryMarkerSource.PLACED_ITEM,
                                -1
                        ),
                        new CategoryMarker(
                                column,
                                "minecraft:torch",
                                CategoryMarkerSource.ITEM_FRAME,
                                -1
                        )
                ),
                List.of(chest)
        );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:torch")
        );
    }

    @Test
    void rejectsDifferentItemsInTheSameStorageColumn() {
        DirectBlockIdCategoryRule rule =
                new DirectBlockIdCategoryRule();
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        BlockPosition column = new BlockPosition(7, -40, 2);

        CategoryRuleResult result = rule.resolve(
                List.of(
                        new CategoryMarker(
                                column,
                                "minecraft:torch",
                                CategoryMarkerSource.PLACED_ITEM,
                                -1
                        ),
                        new CategoryMarker(
                                column,
                                "minecraft:oak_fence",
                                CategoryMarkerSource.SHELF,
                                0
                        )
                ),
                List.of(chest)
        );

        assertFalse(result.succeeded());
        assertTrue(result.message().contains("同一列出现多个物品"));
    }
}
