package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationRoutePlannerTest {
    @Test
    void visitsInterleavedCategoriesInSpatialOrder() {
        ContainerTarget near = target(2, 0);
        ContainerTarget middle = target(10, 0);
        ContainerTarget far = target(20, 0);
        List<ContainerTarget> route = ClassificationRoutePlanner.plan(position(0, 0),
                position(0, 0), List.of(far, near, middle),
                Map.of(near, Set.of("a"), middle, Set.of("b"), far, Set.of("c")),
                Set.of("a", "b", "c"), (first, second) -> 0);

        assertEquals(List.of(near, middle, far), route);
    }

    @Test
    void preservesCapacityPreferenceAndGroupsItemsWithTheSameDestination() {
        ContainerTarget full = target(1, 0);
        ContainerTarget available = target(10, 0);
        ContainerTarget unrelated = target(2, 0);
        List<ContainerTarget> route = ClassificationRoutePlanner.plan(position(0, 0),
                position(0, 0), List.of(full, unrelated, available),
                Map.of(full, Set.of("a"), available, Set.of("a", "b"),
                        unrelated, Set.of("c")), Set.of("a", "b"),
                Comparator.comparingInt(target -> target.equals(available) ? 0 : 1));

        assertEquals(List.of(available), route);
    }

    @Test
    void recognizesExactVariantsAndCategorizedShulkerBoxes() {
        ContainerTarget storage = target(2, 0);
        List<ContainerTarget> route = ClassificationRoutePlanner.plan(position(0, 0),
                position(0, 0), List.of(storage), Map.of(storage, Set.of("minecraft:stone")),
                Set.of("minecraft:stone#variant", "minecraft:shulker_box#x@minecraft:stone"),
                (first, second) -> 0);

        assertEquals(List.of(storage), route);
    }

    @Test
    void includesReturnTripWhenRemovingCrossings() {
        ContainerTarget a = target(0, 10);
        ContainerTarget b = target(10, 10);
        ContainerTarget c = target(10, 0);
        List<ContainerTarget> route = ClassificationRoutePlanner.plan(position(0, 0),
                position(0, 0), List.of(b, a, c),
                Map.of(a, Set.of("a"), b, Set.of("b"), c, Set.of("c")),
                Set.of("a", "b", "c"), (first, second) -> 0);

        assertEquals(3, route.size());
        assertEquals(b, route.get(1));
        assertTrue(route.getFirst().equals(a) || route.getFirst().equals(c));
    }

    @Test
    void emptyAndUnmatchedBatchesHaveNoStops() {
        ContainerTarget storage = target(2, 0);
        assertEquals(List.of(), ClassificationRoutePlanner.plan(position(0, 0),
                position(0, 0), List.of(storage), Map.of(storage, Set.of("a")),
                Set.of("unknown"), (first, second) -> 0));
        assertEquals(List.of(), ClassificationRoutePlanner.plan(position(0, 0),
                position(0, 0), List.of(), Map.of(), Set.of(), (first, second) -> 0));
    }

    private static ContainerTarget target(int x, int z) {
        return new ContainerTarget(position(x, z), "chest");
    }

    private static BlockPosition position(int x, int z) {
        return new BlockPosition(x, 64, z);
    }
}
