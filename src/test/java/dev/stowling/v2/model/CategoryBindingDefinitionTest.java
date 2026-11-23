package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryBindingDefinitionTest {
    @Test
    void supportsNamedBindingsAndLegacyUnnamedDefinitions() {
        var area = new AreaBounds(0, 64, 0, 0, 64, 0);
        var legacy = new CategoryBindingDefinition(UUID.randomUUID(), area, area,
                "DIRECT_BLOCK_ID");
        assertEquals("", legacy.name());
        var named = new CategoryBindingDefinition(legacy.id(), area, area, legacy.ruleId(),
                List.of(), List.of(), null, StoragePolicy.DEFAULT, false, " 石材 ");
        assertEquals("石材", named.name());
        assertThrows(IllegalArgumentException.class, () -> new CategoryBindingDefinition(
                legacy.id(), area, area, legacy.ruleId(), List.of(), List.of(), null,
                StoragePolicy.DEFAULT, false, "a".repeat(41)));
    }

    @Test
    void normalizesIgnoredSupportBlockIds() {
        CategoryBindingDefinition binding = new CategoryBindingDefinition(
                UUID.randomUUID(),
                new AreaBounds(1, 2, 3, 4, 5, 6),
                new AreaBounds(7, 8, 9, 10, 11, 12),
                "DIRECT_BLOCK_ID",
                List.of(
                        " Minecraft:Stone_Bricks ",
                        "minecraft:glass",
                        "minecraft:stone_bricks"
                )
        );

        assertEquals(
                List.of("minecraft:stone_bricks", "minecraft:glass"),
                binding.ignoredMarkerBlockIds()
        );
    }

    @Test
    void normalizesContainerOverrideItemIds() {
        CategoryContainerOverrideDefinition override =
                new CategoryContainerOverrideDefinition(
                        UUID.randomUUID(),
                        new BlockPosition(8, -40, 1),
                        CategoryContainerOverrideMode.REPLACE,
                        List.of(
                                " Minecraft:Quartz_Block ",
                                "minecraft:quartz_slab",
                                "minecraft:quartz_block"
                        )
                );

        assertEquals(
                List.of("minecraft:quartz_block", "minecraft:quartz_slab"),
                override.itemIds()
        );
    }
}
