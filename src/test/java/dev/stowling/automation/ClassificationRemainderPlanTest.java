package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationRemainderPlanTest {
    private final ContainerTarget first = new ContainerTarget(
            new BlockPosition(1, 64, 0), "minecraft:chest");
    private final ContainerTarget second = new ContainerTarget(
            new BlockPosition(2, 64, 0), "minecraft:chest");

    @Test
    void unmatchedItemsDoNotRequireAFullnessCheck() {
        var routes = Map.of("minecraft:stone", List.of(first),
                "minecraft:dirt", List.<ContainerTarget>of());
        assertEquals(Set.of("minecraft:dirt"), ClassificationRemainderPlan.select(routes, true,
                (target, key) -> fail("Unclassified routing must not read storage capacity")));
    }

    @Test
    void overflowRequiresEveryMatchingTargetToBeConfirmedFull() {
        var routes = Map.of("minecraft:stone", List.of(first, second),
                "minecraft:dirt", List.<ContainerTarget>of());
        assertEquals(Set.of(), ClassificationRemainderPlan.select(routes, false,
                (target, key) -> target.equals(first)));
        assertEquals(Set.of("minecraft:stone"), ClassificationRemainderPlan.select(routes, false,
                (target, key) -> true));
    }

    @Test
    void categoriesAccumulateAcrossZonesBeforeSelectingRemainders() {
        var workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(List.of(first), Map.of(first, Set.of("minecraft:stone")),
                List.of());
        workspace.setResolvedCategories(List.of(second), Map.of(second, Set.of("minecraft:dirt")),
                List.of());
        var routes = Map.of("minecraft:stone", workspace.resolvedStorageTargets("minecraft:stone"),
                "minecraft:dirt", workspace.resolvedStorageTargets("minecraft:dirt"),
                "minecraft:sand", workspace.resolvedStorageTargets("minecraft:sand"));
        assertEquals(Set.of("minecraft:sand"), ClassificationRemainderPlan.select(routes, true,
                (target, key) -> false));
    }
}
