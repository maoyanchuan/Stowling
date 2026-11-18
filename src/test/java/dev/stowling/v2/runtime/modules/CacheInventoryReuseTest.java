package dev.stowling.v2.runtime.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CacheInventoryReuseTest {
    @Test
    void laterAreasReuseConfirmedContentsAfterWithdrawalsAndCacheReturns() {
        var cache = target(1);
        UUID region = UUID.randomUUID();
        var scan = ContainerScanResult.success(List.of(cache));
        var workspace = new TaskRunWorkspace();
        workspace.refreshContainerScan(region, scan);
        workspace.recordCacheContents(cache, Map.of("minecraft:stone", 64,
                "minecraft:birch_leaves", 32));
        workspace.recordCacheTransfer(cache, Map.of("minecraft:stone", 64), true);
        workspace.recordCacheTransfer(cache, Map.of("minecraft:birch_leaves", 16), false);
        workspace.refreshContainerScan(region, scan);
        assertEquals(Map.of("minecraft:birch_leaves", 48),
                workspace.cacheContents(cache).orElseThrow());
        assertFalse(workspace.hasRecordedClassifiableStock(cache, Set.of("minecraft:stone")));
        assertTrue(workspace.hasRecordedClassifiableStock(cache, Set.of("minecraft:birch_leaves")));
        workspace.recordCacheTransfer(cache, Map.of("minecraft:birch_leaves", 48), true);
        workspace.refreshContainerScan(region, scan);
        assertEquals(Map.of(), workspace.cacheContents(cache).orElseThrow());
    }

    @Test
    void newlyDiscoveredOrReplacedContainersStillNeedToBeOpened() {
        var cache = target(1);
        var added = target(2);
        UUID region = UUID.randomUUID();
        var workspace = new TaskRunWorkspace();
        workspace.refreshContainerScan(region, ContainerScanResult.success(List.of(cache)));
        workspace.recordCacheContents(cache, Map.of());
        workspace.refreshContainerScan(region, ContainerScanResult.success(List.of(cache, added)));
        assertTrue(workspace.cacheContents(cache).isPresent());
        assertTrue(workspace.cacheContents(added).isEmpty());
        var barrel = new ContainerTarget(cache.position(), "minecraft:barrel");
        workspace.refreshContainerScan(region, ContainerScanResult.success(List.of(barrel)));
        assertTrue(workspace.cacheContents(barrel).isEmpty());
    }

    @Test
    void aDifferentRegionOrNewTaskDoesNotInheritThePreviousInventory() {
        var cache = target(1);
        var scan = ContainerScanResult.success(List.of(cache));
        var workspace = new TaskRunWorkspace();
        workspace.refreshContainerScan(UUID.randomUUID(), scan);
        workspace.recordCacheContents(cache, Map.of());
        workspace.refreshContainerScan(UUID.randomUUID(), scan);
        assertTrue(workspace.cacheContents(cache).isEmpty());
        assertTrue(new TaskRunWorkspace().cacheContents(cache).isEmpty());
    }

    @Test
    void unexpectedWithdrawalInvalidatesTheSnapshotSoTheNextAreaReadsItAgain() {
        var cache = target(1);
        var workspace = new TaskRunWorkspace();
        workspace.recordCacheContents(cache, Map.of("minecraft:stone", 32));
        workspace.recordCacheTransfer(cache, Map.of("minecraft:stone", 64), true);
        assertTrue(workspace.cacheContents(cache).isEmpty());
    }

    @Test
    void emptyBoxesAreSuppliesWhileFilledAndMixedBoxesRemainOverflowStock() {
        assertTrue(ItemIdentity.isEmptyBox("minecraft:shulker_box#empty"));
        assertTrue(ItemIdentity.isEmptyBox("minecraft:red_shulker_box#named"));
        assertFalse(ItemIdentity.isEmptyBox("minecraft:stone"));
        assertFalse(ItemIdentity.isEmptyBox("minecraft:shulker_box#full@minecraft:stone"));
        assertFalse(ItemIdentity.isEmptyBox("minecraft:shulker_box#mixed@stowling:mixed_contents"));
    }

    private static ContainerTarget target(int x) {
        return new ContainerTarget(new BlockPosition(x, 64, 0), "minecraft:chest");
    }
}
