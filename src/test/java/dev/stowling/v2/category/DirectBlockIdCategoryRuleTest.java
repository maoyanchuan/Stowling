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
    void mapsEachMarkerBlockIdToTheConfiguredStorageContainers() {
        DirectBlockIdCategoryRule rule =
                new DirectBlockIdCategoryRule();
        ContainerTarget chest = new ContainerTarget(
                new BlockPosition(7, -34, 6),
                "minecraft:chest"
        );

        CategoryRuleResult result = rule.resolve(
                List.of("minecraft:iron_block", "minecraft:gold_block"),
                List.of(chest)
        );

        assertTrue(result.succeeded());
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:iron_block")
        );
        assertEquals(
                List.of(chest),
                result.categories().get("minecraft:gold_block")
        );
        assertTrue(rule.matchesItem(
                "minecraft:iron_block",
                "minecraft:iron_block"
        ));
        assertFalse(rule.matchesItem(
                "minecraft:iron_block",
                "minecraft:iron_ingot"
        ));
    }
}
