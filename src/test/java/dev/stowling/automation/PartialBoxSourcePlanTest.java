package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartialBoxSourcePlanTest {
    @Test
    void everyChestInTheDedicatedPartialRegionIsIncluded() {
        ContainerTarget cache = target(0);
        List<ContainerTarget> partials = List.of(target(10), target(11), target(12));
        assertEquals(List.of(cache, target(10), target(11), target(12)),
                PartialBoxSourcePlan.combine(List.of(cache), partials, List.of(target(20))));
        assertEquals(partials,
                PartialBoxSourcePlan.combine(List.of(), partials, List.of(target(20))));
    }

    @Test
    void warehouseOutputsAreNotBorrowedAsPartialCacheSources() {
        var warehouse = new ContainerTarget(target(20).position(), "minecraft:chest",
                null, null, target(21).position());
        assertEquals(List.of(target(10)), PartialBoxSourcePlan.combine(List.of(),
                List.of(target(20), target(21), target(10)), List.of(warehouse)));
    }

    @Test
    void repeatedRegionEntriesAndDoubleChestHalvesAreReadOnlyOnce() {
        var pair = new ContainerTarget(target(10).position(), "minecraft:chest",
                null, null, target(11).position());
        assertEquals(List.of(pair, target(12)), PartialBoxSourcePlan.combine(List.of(pair),
                List.of(pair, target(11), target(12)), List.of()));
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, -44, 30), "minecraft:chest");
    }
}
