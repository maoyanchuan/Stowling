package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationBatchRouteTest {
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
