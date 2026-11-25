package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassificationVisitPolicyTest {
    private static final String STONE = "minecraft:stone";
    private static final String DIRT = "minecraft:dirt";
    private static final ContainerTarget SOURCE = target(0);
    private static final ContainerTarget FIRST = target(10);
    private static final ContainerTarget SECOND = target(11);
    private static final ContainerTarget OTHER = target(12);

    @Test
    void fullCargoWaitsWhileOtherItemsAreDeliveredWithoutLosingOwnership() {
        var workspace = workspace(true);
        workspace.recordWithdrawn(STONE, 64);
        workspace.recordWithdrawn(DIRT, 32);
        workspace.recordConfirmedFullStorage(FIRST, Set.of(STONE));
        workspace.recordConfirmedFullStorage(SECOND, Set.of(STONE));
        workspace.markTargetUnavailable(FIRST);
        workspace.markTargetUnavailable(SECOND);
        assertEquals(Map.of(DIRT, 32), workspace.carriedItems());
        assertEquals(OTHER, workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.consumeDeposit("carried", Map.of(DIRT, 32), Map.of());
        assertFalse(workspace.hasCarriedItems());
        assertTrue(workspace.hasManagedItems());
        assertEquals(Map.of(STONE, 64), workspace.managedItems());
        assertFalse(workspace.cacheHasMore());
        workspace.endClassificationDelivery();
        assertEquals(Map.of(STONE, 64), workspace.carriedItems());
    }

    @Test
    void stillTriesAnotherSameItemTargetAndDoesNotTreatNavigationFailureAsFull() {
        var workspace = workspace(true);
        workspace.recordWithdrawn(STONE, 64);
        workspace.recordConfirmedFullStorage(FIRST, Set.of(STONE));
        workspace.markTargetUnavailable(FIRST);
        assertEquals(SECOND, workspace.selectStorageTarget("carried", true).orElseThrow());
        workspace.markTargetUnavailable(SECOND, "navigation failed");
        assertEquals(Map.of(STONE, 64), workspace.carriedItems());
        assertEquals(SOURCE, workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void laterZoneCanAcceptRetainedCargoAndASeparateRunCannotInheritFullResults() {
        var workspace = workspace(true);
        workspace.recordWithdrawn(STONE, 64);
        workspace.recordConfirmedFullStorage(FIRST, Set.of(STONE));
        workspace.recordConfirmedFullStorage(SECOND, Set.of(STONE));
        assertFalse(workspace.hasCarriedItems());
        var later = target(20);
        workspace.setResolvedCategories(List.of(later), Map.of(later, Set.of(STONE)),
                List.of(SOURCE));
        assertEquals(Map.of(STONE, 64), workspace.carriedItems());
        assertEquals(later, workspace.selectStorageTarget("carried", false).orElseThrow());
        var nextRun = workspace(true);
        nextRun.recordWithdrawn(STONE, 64);
        assertTrue(nextRun.hasCarriedItems());
    }

    @Test
    void disabledHandoffKeepsTheOriginalReturnToSourcePath() {
        var workspace = workspace(false);
        workspace.recordWithdrawn(STONE, 64);
        workspace.recordConfirmedFullStorage(FIRST, Set.of(STONE));
        workspace.recordConfirmedFullStorage(SECOND, Set.of(STONE));
        workspace.markTargetUnavailable(FIRST);
        workspace.markTargetUnavailable(SECOND);
        assertTrue(workspace.hasCarriedItems());
        assertEquals(SOURCE, workspace.selectStorageTarget("carried", true).orElseThrow());
    }

    @Test
    void auxiliaryStockCannotTriggerItsOwnDemandAndOnlyRequestedCategoriesRemain() {
        var workspace = new TaskRunWorkspace();
        workspace.configureClassificationVisits(false, true, false);
        var auxiliary = target(2);
        workspace.recordCacheContents(SOURCE, Map.of());
        workspace.recordCacheContents(auxiliary, Map.of(STONE, 64, DIRT, 64));
        assertTrue(workspace.classificationDemand(List.of(SOURCE), Set.of(STONE, DIRT)).isEmpty());
        workspace.setClassificationDemand(Set.of());
        workspace.setResolvedCategories(List.of(FIRST), Map.of(FIRST, Set.of(STONE, DIRT)),
                List.of(auxiliary));
        assertFalse(workspace.hasRecordedClassifiableStock(auxiliary, Set.of(STONE, DIRT)));
        workspace.recordCacheContents(SOURCE, Map.of(STONE, 32));
        var demand = workspace.classificationDemand(List.of(SOURCE), Set.of(STONE, DIRT));
        assertEquals(Set.of(STONE), demand);
        workspace.setClassificationDemand(demand);
        assertEquals(Set.of(STONE), workspace.remainingCacheCategories(auxiliary));
    }

    @Test
    void mixedBoxContentsAndCarriedCargoTriggerTheirActualCategories() {
        var workspace = new TaskRunWorkspace();
        workspace.configureClassificationVisits(false, true, false);
        String mixed = "minecraft:shulker_box#mixed@stowling:mixed_contents";
        workspace.recordClassificationBoxContents(mixed, Set.of(STONE, "minecraft:oak_log"));
        workspace.recordCacheContents(SOURCE, Map.of(mixed, 1, "minecraft:shulker_box", 9));
        workspace.recordWithdrawn(DIRT, 16);
        assertEquals(Set.of(STONE, DIRT),
                workspace.classificationDemand(List.of(SOURCE), Set.of(STONE, DIRT)));
    }

    @Test
    void switchesAreIndependentOfBatchingAndOfOtherWorkspaces() {
        for (int mask = 0; mask < 8; mask++) {
            var workspace = new TaskRunWorkspace();
            workspace.configureClassificationVisits((mask & 1) != 0, (mask & 2) != 0,
                    (mask & 4) != 0);
            assertEquals((mask & 1) != 0, workspace.fullTargetHandoffEnabled());
            assertEquals((mask & 2) != 0, workspace.partialCacheOnDemandEnabled());
            assertEquals((mask & 4) != 0, workspace.cachedEmptyBoxSettlementEnabled());
            assertTrue(workspace.sameCategoryBoxBatching());
            workspace.configureClassificationBaseline(true);
            assertFalse(workspace.sameCategoryBoxBatching());
            assertEquals((mask & 1) != 0, workspace.fullTargetHandoffEnabled());
            var ordinary = new TaskRunWorkspace();
            assertFalse(ordinary.fullTargetHandoffEnabled());
            assertFalse(ordinary.partialCacheOnDemandEnabled());
            assertFalse(ordinary.cachedEmptyBoxSettlementEnabled());
        }
    }

    @Test
    void emptyBoxRecordsSurviveZoneChangesAndFollowConfirmedTransfers() {
        var workspace = new TaskRunWorkspace();
        workspace.configureClassificationVisits(false, false, true);
        String empty = "minecraft:shulker_box";
        workspace.recordSettlementSource(SOURCE, Map.of(empty, 12));
        workspace.invalidateCacheInventory();
        assertEquals(Map.of(empty, 12), workspace.settlementContents(SOURCE));
        workspace.recordCacheTransfer(SOURCE, Map.of(empty, 3), true);
        assertEquals(Map.of(empty, 9), workspace.settlementContents(SOURCE));
        workspace.recordCacheContents(SOURCE, Map.of(empty, 8));
        assertEquals(Map.of(empty, 8), workspace.settlementContents(SOURCE));
        var staging = new ContainerTarget(SOURCE.position(), SOURCE.type(), null,
                new dev.stowling.v2.model.StoragePolicy(
                        dev.stowling.v2.model.StorageMode.BOX_ONLY, 0, true, null));
        workspace.recordSettlementSource(staging, Map.of(empty, 7));
        assertEquals(Map.of(empty, 7), workspace.settlementContents(SOURCE));
        assertEquals(List.of(SOURCE), workspace.recordedSettlementSources());
        var ordinary = new TaskRunWorkspace();
        ordinary.recordSettlementSource(SOURCE, Map.of(empty, 12));
        assertTrue(ordinary.recordedSettlementSources().isEmpty());
    }

    private static TaskRunWorkspace workspace(boolean handoff) {
        var workspace = new TaskRunWorkspace();
        workspace.configureClassificationVisits(handoff, false, false);
        workspace.setResolvedCategories(List.of(FIRST, SECOND, OTHER),
                Map.of(FIRST, Set.of(STONE), SECOND, Set.of(STONE), OTHER, Set.of(DIRT)),
                List.of(SOURCE));
        workspace.selectClassificationSource(SOURCE);
        workspace.recordCacheContents(SOURCE, Map.of());
        return workspace;
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }
}
