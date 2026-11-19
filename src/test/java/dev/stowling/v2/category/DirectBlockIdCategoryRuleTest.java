package dev.stowling.v2.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DirectBlockIdCategoryRuleTest {
    @Test
    void keepsRepeatedCategoryMarkersInTheirOriginalLayoutPositions() {
        ContainerTarget first = new ContainerTarget(new BlockPosition(0, 64, 0), "chest");
        ContainerTarget middle = new ContainerTarget(new BlockPosition(1, 64, 0), "chest");
        ContainerTarget last = new ContainerTarget(new BlockPosition(2, 64, 0), "chest");
        CategoryRuleResult result = new DirectBlockIdCategoryRule().resolve(List.of(
                new CategoryMarker(new BlockPosition(0, 64, 1), "minecraft:stone"),
                new CategoryMarker(new BlockPosition(1, 64, 1), "minecraft:dirt"),
                new CategoryMarker(new BlockPosition(2, 64, 1), "minecraft:stone")),
                List.of(first, middle, last));

        assertTrue(result.succeeded());
        assertEquals(List.of("minecraft:stone", "minecraft:dirt", "minecraft:stone"),
                result.layout().stream().map(entry -> entry.itemId()).toList());
        assertEquals(List.of(List.of(first), List.of(middle), List.of(last)),
                result.layout().stream().map(entry -> entry.containers()).toList());
        assertEquals(List.of(first, last), result.categories().get("minecraft:stone"));
    }

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

    @Test
    void appendsItemsToAnAutomaticallyClassifiedContainer() {
        DirectBlockIdCategoryRule rule =
                new DirectBlockIdCategoryRule();
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        CategoryRuleResult result = rule.resolve(
                List.of(new CategoryMarker(
                        new BlockPosition(7, -40, 2),
                        "minecraft:oak_log"
                )),
                List.of(chest),
                List.of(new CategoryContainerOverrideDefinition(
                        UUID.randomUUID(),
                        chest.position(),
                        CategoryContainerOverrideMode.ADD,
                        List.of("minecraft:oak_planks")
                ))
        );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:oak_log")
        );
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:oak_planks")
        );
    }

    @Test
    void replacesAmbiguousAutomaticCategoriesForOneContainer() {
        DirectBlockIdCategoryRule rule =
                new DirectBlockIdCategoryRule();
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        BlockPosition markerColumn = new BlockPosition(7, -39, 2);
        CategoryRuleResult result = rule.resolve(
                List.of(
                        new CategoryMarker(
                                markerColumn,
                                "minecraft:torch",
                                CategoryMarkerSource.PLACED_ITEM,
                                -1
                        ),
                        new CategoryMarker(
                                markerColumn,
                                "minecraft:quartz_block",
                                CategoryMarkerSource.ITEM_FRAME,
                                -1
                        )
                ),
                List.of(chest),
                List.of(new CategoryContainerOverrideDefinition(
                        UUID.randomUUID(),
                        chest.position(),
                        CategoryContainerOverrideMode.REPLACE,
                        List.of(
                                "minecraft:quartz_block",
                                "minecraft:quartz_slab"
                        )
                ))
        );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:quartz_block")
        );
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:quartz_slab")
        );
        assertFalse(result.categories().containsKey("minecraft:torch"));
    }

    @Test
    void explicitTaskSourcesMergeAllSelectedMarkerKinds() {
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        BlockPosition column = new BlockPosition(7, -40, 2);

        CategoryRuleResult result =
                DirectBlockIdCategoryRule.resolveExplicitSources(
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
                                ),
                                new CategoryMarker(
                                        column,
                                        "minecraft:torch",
                                        CategoryMarkerSource.SHELF,
                                        0
                                ),
                                new CategoryMarker(
                                        column,
                                        "minecraft:torch",
                                        CategoryMarkerSource.FLOWER_POT,
                                        -1
                                ),
                                new CategoryMarker(
                                        column,
                                        "minecraft:torch",
                                        CategoryMarkerSource.CONTAINER_CONTENTS,
                                        0
                                )
                        ),
                        List.of(chest),
                        List.of()
                );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:torch")
        );
    }

    @Test
    void explicitTaskSourcesMergeMultipleShelfSlotsInOneColumn() {
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        BlockPosition column = new BlockPosition(7, -40, 2);

        CategoryRuleResult result =
                DirectBlockIdCategoryRule.resolveExplicitSources(
                        List.of(
                                new CategoryMarker(
                                        column,
                                        "minecraft:torch",
                                        CategoryMarkerSource.SHELF,
                                        0
                                ),
                                new CategoryMarker(
                                        column,
                                        "minecraft:lantern",
                                        CategoryMarkerSource.SHELF,
                                        1
                                ),
                                new CategoryMarker(
                                        column,
                                        "minecraft:oak_sign",
                                        CategoryMarkerSource.CONTAINER_CONTENTS,
                                        0
                                )
                        ),
                        List.of(chest),
                        List.of()
                );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:torch")
        );
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:lantern")
        );
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:oak_sign")
        );
    }
    @Test
    void explicitTaskSourcesMergeDifferentItemsInOneColumn() {
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        BlockPosition column = new BlockPosition(7, -40, 2);

        CategoryRuleResult result =
                DirectBlockIdCategoryRule.resolveExplicitSources(
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
                                        CategoryMarkerSource.ITEM_FRAME,
                                        -1
                                )
                        ),
                        List.of(chest),
                        List.of()
                );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:torch")
        );
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:oak_fence")
        );
    }

    @Test
    void explicitTaskSourcesApplyReplaceOverrideOnce() {
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        BlockPosition column = new BlockPosition(7, -40, 2);

        CategoryRuleResult result =
                DirectBlockIdCategoryRule.resolveExplicitSources(
                        List.of(
                                new CategoryMarker(
                                        column,
                                        "minecraft:torch",
                                        CategoryMarkerSource.PLACED_ITEM,
                                        -1
                                ),
                                new CategoryMarker(
                                        column,
                                        "minecraft:quartz_block",
                                        CategoryMarkerSource.CONTAINER_CONTENTS,
                                        0
                                )
                        ),
                        List.of(chest),
                        List.of(new CategoryContainerOverrideDefinition(
                                UUID.randomUUID(),
                                chest.position(),
                                CategoryContainerOverrideMode.REPLACE,
                                List.of(
                                        "minecraft:quartz_block",
                                        "minecraft:quartz_slab"
                                )
                        ))
                );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:quartz_block")
        );
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:quartz_slab")
        );
        assertFalse(result.categories().containsKey("minecraft:torch"));
    }

    @Test
    void explicitTaskSourcesReturnEmptyWhenBindingHasNoMarkers() {
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );

        CategoryRuleResult result =
                DirectBlockIdCategoryRule.resolveExplicitSources(
                        List.of(),
                        List.of(chest),
                        List.of()
                );

        assertTrue(result.succeeded());
        assertTrue(result.categories().isEmpty());
    }
}
