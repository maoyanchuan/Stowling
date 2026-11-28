package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassificationLooseBatchPlanTest {
    private static final String LOG = "minecraft:oak_log";
    private static final String STAIRS = "minecraft:oak_stairs";
    private static final String STRIPPED = "minecraft:stripped_oak_log";
    private static final ContainerTarget FIRST = target(0);
    private static final ContainerTarget SECOND = target(1);

    @Test
    void reportedThreeItemsVisitEachSourceOnceWithAllOfItsCategories() {
        var stock = new LinkedHashMap<ContainerTarget, Map<String, Integer>>();
        stock.put(FIRST, Map.of(LOG, 1, STAIRS, 1));
        stock.put(SECOND, Map.of(STRIPPED, 1));
        var result = ClassificationLooseBatchPlan.plan(stock,
                Map.of(LOG, 64, STAIRS, 64, STRIPPED, 64), List.of(), 34, Map.of(), 27);
        assertEquals(List.of(FIRST, SECOND), List.copyOf(result.keySet()));
        assertEquals(Map.of(LOG, 1, STAIRS, 1), result.get(FIRST));
        assertEquals(Map.of(STRIPPED, 1), result.get(SECOND));
    }

    @Test
    void quotaPackingAndExactItemRulesCannotBeBypassed() {
        for (var items : List.of(Map.of(LOG, 65), Map.of("minecraft:shulker_box", 1),
                Map.of(LOG, 1, STAIRS, 1), Map.of(LOG + "#named@stowling:named_item", 1))) {
            assertTrue(ClassificationLooseBatchPlan.plan(Map.of(FIRST, items), Map.of(LOG, 64),
                    List.of(), 34, Map.of(), 27).isEmpty());
        }
        assertTrue(ClassificationLooseBatchPlan.plan(Map.of(FIRST, Map.of(LOG, 32),
                SECOND, Map.of(LOG, 33)), Map.of(LOG, 64), List.of(), 34, Map.of(), 27).isEmpty());
    }

    @Test
    void batchLimitInventoryReserveComponentsAndUnstackableSizesAreRespected() {
        var stock = Map.of(FIRST, Map.of(LOG, 64, STAIRS, 64));
        var limits = Map.of(LOG, 128, STAIRS, 128);
        assertTrue(ClassificationLooseBatchPlan.plan(stock, limits,
                List.of(), 34, Map.of(), 1).isEmpty());
        assertTrue(ClassificationLooseBatchPlan.plan(stock, limits,
                List.of(), 1, Map.of(), 27).isEmpty());
        assertTrue(ClassificationLooseBatchPlan.plan(Map.of(FIRST, Map.of(LOG, 2)), limits,
                List.of(), 34, Map.of(LOG, 1), 1).isEmpty());
        var occupied = List.of(new ShulkerTransferPlanner.Slot(LOG, 32, 64));
        assertEquals(Map.of(FIRST, Map.of(LOG, 32)), ClassificationLooseBatchPlan.plan(
                Map.of(FIRST, Map.of(LOG, 32)), limits, occupied, 1, Map.of(LOG, 64), 1));
        assertTrue(ClassificationLooseBatchPlan.plan(Map.of(FIRST, Map.of(LOG + "#one", 1)),
                limits, List.of(new ShulkerTransferPlanner.Slot(LOG + "#two", 1, 64)),
                1, Map.of(), 1).isEmpty());
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }
}
