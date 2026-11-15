package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

class MinecraftContainerRegionScannerTest {
    @Test
    void collapsesEachDoubleChestPairToItsLowestPosition() {
        ContainerTarget chestLeft = target(0, 64, 0, "minecraft:chest");
        ContainerTarget chestRight = target(1, 64, 0, "minecraft:chest");
        ContainerTarget barrel = target(2, 64, 0, "minecraft:barrel");
        ContainerTarget trappedLeft = target(
                3,
                64,
                0,
                "minecraft:trapped_chest"
        );
        ContainerTarget trappedRight = target(
                4,
                64,
                0,
                "minecraft:trapped_chest"
        );

        List<ContainerTarget> result =
                MinecraftContainerRegionScanner.collapseDoubleChestPairs(
                        List.of(
                                chestLeft,
                                chestRight,
                                barrel,
                                trappedLeft,
                                trappedRight
                        )
                );

        assertEquals(
                List.of(chestLeft, barrel, trappedLeft),
                result
        );
    }

    @Test
    void leavesConnectedOddShapedChestGroupsUntouched() {
        ContainerTarget first = target(0, 64, 0, "minecraft:chest");
        ContainerTarget corner = target(1, 64, 0, "minecraft:chest");
        ContainerTarget side = target(1, 64, 1, "minecraft:chest");

        List<ContainerTarget> result =
                MinecraftContainerRegionScanner.collapseDoubleChestPairs(
                        List.of(first, corner, side)
                );

        assertEquals(List.of(first, corner, side), result);
    }

    private static ContainerTarget target(
            int x,
            int y,
            int z,
            String type
    ) {
        return new ContainerTarget(new BlockPosition(x, y, z), type);
    }
}
