package dev.stowling.v2.category;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerContentsCategoryRuleTest {
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
