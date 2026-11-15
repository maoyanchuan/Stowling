package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryBindingDefinitionTest {
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
}
