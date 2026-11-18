package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationBatchRouteTest {
    @Test
    void mixedOnlyCacheChestsAreKeptAndTheirContentsCanBeRefreshedAfterUnpacking() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        ContainerTarget first = target(0);
        ContainerTarget second = target(1);
        String mixed = "minecraft:shulker_box#mixed@" + ItemIdentity.MIXED_BOX_CATEGORY;
        workspace.recordCacheInventory(Map.of(first.position(), Map.of(mixed, 1),
                second.position(), Map.of(mixed, 1)));

        assertTrue(workspace.hasRecordedClassifiableStock(first, Set.of("minecraft:stone")));
        assertTrue(workspace.hasMixedCacheStock(first));
        workspace.recordCacheContents(first, Map.of("minecraft:dirt", 64));
        assertFalse(workspace.hasMixedCacheStock(first));
        assertFalse(workspace.hasRecordedClassifiableStock(first, Set.of("minecraft:stone")));
        assertTrue(workspace.hasMixedCacheStock(second));
    }

    @Test
    void keepsTheBatchRouteWhenTheSelectionPreferenceChanges() {
        TaskRunWorkspace workspace = workspace();
        ContainerTarget first = workspace.selectStorageTarget("carried", true).orElseThrow();
        assertEquals(target(2), first);
        workspace.consumeDeposit("carried", Map.of("a", 64), Map.of());

        assertEquals(target(10), workspace.selectStorageTarget("carried", true,
                (left, right) -> Integer.compare(right.position().x(), left.position().x()))
                .orElseThrow());
        workspace.consumeDeposit("carried", Map.of("b", 64), Map.of());
        assertEquals(target(20), workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void skipsFailedStopsAndRetriesAlternativesBeforeReturningToCache() {
        TaskRunWorkspace workspace = workspace();
        workspace.selectStorageTarget("carried", true).orElseThrow();
        workspace.markTargetUnavailable(target(10));
        workspace.consumeDeposit("carried", Map.of("a", 64), Map.of());
        assertEquals(target(20), workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.consumeDeposit("carried", Map.of("c", 64), Map.of());
        assertEquals(target(30), workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.consumeDeposit("carried", Map.of("b", 64), Map.of("b", 64));
        assertEquals(target(0), workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void newWithdrawalResetsTriedStopsAndTheRoute() {
        TaskRunWorkspace workspace = workspace();
        workspace.selectStorageTarget("carried", true).orElseThrow();
        workspace.consumeDeposit("carried", Map.of("a", 64, "b", 64, "c", 64), Map.of());
        workspace.recordWithdrawn("a", 64);
        workspace.resetStorageSelection();
        assertEquals(target(2), workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    private static TaskRunWorkspace workspace() {
        TaskRunWorkspace workspace = new TaskRunWorkspace();
        workspace.setResolvedCategories(List.of(target(20), target(2), target(10), target(30)),
                Map.of(target(2), Set.of("a"), target(10), Set.of("b"),
                        target(20), Set.of("c"), target(30), Set.of("b")), List.of(target(0)));
        workspace.selectNextContainer().orElseThrow();
        workspace.recordWithdrawn("a", 64);
        workspace.recordWithdrawn("b", 64);
        workspace.recordWithdrawn("c", 64);
        return workspace;
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "chest");
    }
}
