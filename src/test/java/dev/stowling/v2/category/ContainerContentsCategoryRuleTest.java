package dev.stowling.v2.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.CategoryContainerOverrideDefinition;
import dev.stowling.v2.model.CategoryContainerOverrideMode;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContainerContentsCategoryRuleTest {
    @Test
    void wholeStorageMapsRotatedMarkerToAllHeightsAndKeepsReplacementOverrides() {
        ContainerContentsCategoryRule rule = new ContainerContentsCategoryRule();
        List<ContainerTarget> storage = List.of(
                new ContainerTarget(new BlockPosition(10, -38, 46), "minecraft:chest"),
                new ContainerTarget(new BlockPosition(10, -37, 46), "minecraft:chest"),
                new ContainerTarget(new BlockPosition(10, -36, 47), "minecraft:chest"));
        List<CategoryMarker> markers = List.of(
                new CategoryMarker(new BlockPosition(11, -40, 46), "minecraft:stone",
                        CategoryMarkerSource.CONTAINER_CONTENTS, 0),
                new CategoryMarker(new BlockPosition(10, -40, 46), "minecraft:dirt",
                        CategoryMarkerSource.CONTAINER_CONTENTS, 1));
        CategoryContainerOverrideDefinition override = new CategoryContainerOverrideDefinition(
                UUID.randomUUID(), storage.get(2).position(), CategoryContainerOverrideMode.REPLACE,
                List.of("minecraft:oak_log"));

        CategoryRuleResult result = rule.resolve(markers, storage, List.of(override), true);

        assertTrue(result.succeeded());
        assertEquals(storage.subList(0, 2), result.categories().get("minecraft:stone"));
        assertEquals(storage.subList(0, 2), result.categories().get("minecraft:dirt"));
        assertEquals(List.of(storage.get(2)), result.categories().get("minecraft:oak_log"));
        CategoryRuleResult explicit = DirectBlockIdCategoryRule.resolveExplicitSources(
                markers, storage, List.of(override), true);
        assertEquals(result.categories(), explicit.categories());
        assertTrue(!rule.resolve(markers, storage).succeeded());
    }

    @Test
    void mapsEveryCachedItemFromOneMarkerContainerToItsStorageColumn() {
        ContainerContentsCategoryRule rule =
                new ContainerContentsCategoryRule();
        ContainerTarget storage = new ContainerTarget(
                new BlockPosition(7, -40, 1),
                "minecraft:chest"
        );
        BlockPosition marker = new BlockPosition(7, -40, 2);
        CategoryRuleResult result = rule.resolve(
                List.of(
                        new CategoryMarker(
                                marker,
                                "minecraft:oak_log",
                                CategoryMarkerSource.CONTAINER_CONTENTS,
                                0
                        ),
                        new CategoryMarker(
                                marker,
                                "minecraft:oak_planks",
                                CategoryMarkerSource.CONTAINER_CONTENTS,
                                1
                        ),
                        new CategoryMarker(
                                marker,
                                "minecraft:oak_slab",
                                CategoryMarkerSource.CONTAINER_CONTENTS,
                                2
                        )
                ),
                List.of(storage)
        );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(storage),
                result.categories().get("minecraft:oak_log")
        );
        assertEquals(
                List.of(storage),
                result.categories().get("minecraft:oak_planks")
        );
        assertEquals(
                List.of(storage),
                result.categories().get("minecraft:oak_slab")
        );
    }
}
