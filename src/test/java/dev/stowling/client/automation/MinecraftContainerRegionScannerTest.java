package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
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
                        ),
                        Map.of(
                                chestLeft.position(),
                                chestRight.position(),
                                chestRight.position(),
                                chestLeft.position(),
                                trappedLeft.position(),
                                trappedRight.position(),
                                trappedRight.position(),
                                trappedLeft.position()
                        )
                );

        assertEquals(
                List.of(pair(chestLeft, chestRight), barrel, pair(trappedLeft, trappedRight)),
                result
        );
    }

    @Test
    void collapsesLargeChestPairsInsideDenseChestWall() {
        ContainerTarget first = target(0, 64, 0, "minecraft:chest");
        ContainerTarget firstPartner = target(1, 64, 0, "minecraft:chest");
        ContainerTarget secondPartner = target(0, 64, 1, "minecraft:chest");
        ContainerTarget second = target(1, 64, 1, "minecraft:chest");

        List<ContainerTarget> result =
                MinecraftContainerRegionScanner.collapseDoubleChestPairs(
                        List.of(
                                first,
                                firstPartner,
                                secondPartner,
                                second
                        ),
                        Map.of(
                                first.position(),
                                firstPartner.position(),
                                firstPartner.position(),
                                first.position(),
                                second.position(),
                                secondPartner.position(),
                                secondPartner.position(),
                                second.position()
                        )
                );

        assertEquals(List.of(pair(first, firstPartner), pair(secondPartner, second)), result);
    }

    @Test
    void keepsBothEntrancesWhenTheRegionIncludesOnlyTheOtherHalf() {
        ContainerTarget first = target(68, -35, 29, "minecraft:chest");
        ContainerTarget second = target(68, -35, 30, "minecraft:chest");

        assertEquals(List.of(pair(first, second)),
                MinecraftContainerRegionScanner.collapseDoubleChestPairs(List.of(second),
                        Map.of(second.position(), first.position())));
    }

    private static ContainerTarget pair(ContainerTarget first, ContainerTarget second) {
        return new ContainerTarget(first.position(), first.type(), first.standingArea(),
                first.storagePolicy(), second.position());
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
