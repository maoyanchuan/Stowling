package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MixedBoxSelectionTest {
    private static final String STONE = "minecraft:stone";
    private static final String LEAVES = "minecraft:birch_leaves";
    private static final String UNKNOWN = "minecraft:dirt";
    private static final ContainerTarget CACHE = target(0);
    private static final ContainerTarget STONE_CHEST = target(2);
    private static final ContainerTarget LEAF_CHEST = target(3);

    @Test
    void takesOnlyContentsThatHaveADestinationInTheCurrentClassificationArea() {
        TaskRunWorkspace workspace = workspace();
        Set<String> selected = workspace.mixedBoxWithdrawalItems(CACHE,
                Set.of(STONE, LEAVES, UNKNOWN));
        assertEquals(Set.of(STONE, LEAVES), selected);
        assertTrue(workspace.carriedItems().isEmpty());
        assertTrue(workspace.mixedBoxWithdrawalItems(CACHE, Set.of(UNKNOWN)).isEmpty());
    }

    @Test
    void preservesExactComponentKeysWhileMatchingTheirBaseCategory() {
        String variant = STONE + "#variant";
        assertEquals(Set.of(variant), workspace().mixedBoxWithdrawalItems(CACHE,
                Set.of(variant, UNKNOWN + "#variant")));
    }

    @Test
    void skipsCategoriesWhoseDestinationsHaveAllBeenRejected() {
        TaskRunWorkspace workspace = workspace();
        workspace.markTargetUnavailable(STONE_CHEST);
        assertEquals(Set.of(LEAVES), workspace.mixedBoxWithdrawalItems(CACHE,
                Set.of(STONE, LEAVES, UNKNOWN)));
        workspace.markTargetUnavailable(LEAF_CHEST);
        assertTrue(workspace.mixedBoxWithdrawalItems(CACHE, Set.of(STONE, LEAVES)).isEmpty());
    }

    @Test
    void allowsAnItemWhenAnotherDestinationForThatCategoryIsStillAvailable() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(List.of(STONE_CHEST, LEAF_CHEST),
                Map.of(STONE_CHEST, Set.of(STONE), LEAF_CHEST, Set.of(STONE)), List.of(CACHE));
        workspace.selectNextContainer().orElseThrow();
        workspace.markTargetUnavailable(STONE_CHEST);
        assertEquals(Set.of(STONE), workspace.mixedBoxWithdrawalItems(CACHE, Set.of(STONE)));
    }

    @Test
    void doesNotRepeatedlyExtractItemsAlreadyReturnedToThisCache() {
        TaskRunWorkspace workspace = workspace();
        workspace.deferReturnedCacheItems(Map.of(STONE, 64), Map.of());
        assertEquals(Set.of(LEAVES), workspace.mixedBoxWithdrawalItems(CACHE,
                Set.of(STONE, LEAVES, UNKNOWN)));
        workspace.deferReturnedCacheItems(Map.of(LEAVES, 32), Map.of());
        assertTrue(workspace.mixedBoxWithdrawalItems(CACHE,
                Set.of(STONE, LEAVES, UNKNOWN)).isEmpty());
    }

    @Test
    void leavesOtherAreasContentsForThatAreasLaterClassificationPass() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(List.of(STONE_CHEST), Map.of(STONE_CHEST, Set.of(STONE)),
                List.of(CACHE));
        workspace.selectNextContainer().orElseThrow();
        assertEquals(Set.of(STONE), workspace.mixedBoxWithdrawalItems(CACHE,
                Set.of(STONE, LEAVES, UNKNOWN)));
        workspace.setResolvedCategories(List.of(LEAF_CHEST), Map.of(LEAF_CHEST, Set.of(LEAVES)),
                List.of(CACHE));
        workspace.selectNextContainer().orElseThrow();
        assertEquals(Set.of(LEAVES), workspace.mixedBoxWithdrawalItems(CACHE,
                Set.of(LEAVES, UNKNOWN)));
    }

    @Test
    void theSourceCacheIsNotItsOwnClassificationDestination() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(List.of(CACHE), Map.of(CACHE, Set.of(STONE)), List.of(CACHE));
        workspace.selectNextContainer().orElseThrow();
        assertTrue(workspace.mixedBoxWithdrawalItems(CACHE, Set.of(STONE, UNKNOWN)).isEmpty());
    }

    private static TaskRunWorkspace workspace() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(List.of(STONE_CHEST, LEAF_CHEST),
                Map.of(STONE_CHEST, Set.of(STONE), LEAF_CHEST, Set.of(LEAVES)), List.of(CACHE));
        workspace.selectNextContainer().orElseThrow();
        return workspace;
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }
}
