package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryZoneOptionsTest {
    @Test
    void explicitMixedReservePacksOnlyTheTwoExcessItemsAndOneIncomingItem() {
        var previous = new StoragePolicy(StorageMode.MIXED, 50, false, null, false, 27, null);
        var policy = bulkOptions(27).storagePolicy(previous);
        assertTrue(policy.allowPartialBoxes());
        for (int size : List.of(16, 64)) {
            int reserve = StoragePackingPlanner.looseLimit(policy, 54, size);
            int loose = 27 * size + 2;
            int incoming = 1;
            int inside = 26 * size;
            int taken = StoragePackingPlanner.targetItemsForPacking(policy, incoming, loose,
                    reserve, inside, size);
            assertEquals(2, taken);
            assertEquals(27 * size, loose - taken);
            assertTrue(StoragePackingPlanner.canPack(policy.allowPartialBoxes(),
                    incoming + taken, inside, size));
            assertEquals(26 * size + 3, inside + incoming + taken);
            assertTrue(inside + incoming + taken < 27 * size);
            assertNull(policy.partialBoxDestinationRef());
        }
    }

    @Test
    void explicitMixedReserveAllowsANewPartialBoxWhenNoExistingBoxHasRoom() {
        var previous = new StoragePolicy(StorageMode.MIXED, 50, false, null, false, 27, null);
        var policy = bulkOptions(27).storagePolicy(previous);
        assertTrue(StoragePackingPlanner.shouldBringBox(policy, 1, 0, 64, 2, 0, 27 * 64 + 2));
        int taken = StoragePackingPlanner.targetItemsForPacking(policy, 1, 27 * 64 + 2,
                27 * 64, 0, 64);
        assertEquals(2, taken);
        assertTrue(StoragePackingPlanner.canPack(policy.allowPartialBoxes(), 1 + taken, 0, 64));
        assertEquals(27 * 64, 27 * 64 + 2 - taken);
    }

    @Test
    void bulkStacksControlMixedPackingAndKeepLegacyAllocationWithoutTheField() {
        var previous = new StoragePolicy(StorageMode.MIXED, 25, true, null, true, 4, null);
        for (int stacks : List.of(0, 9, 27, 54)) {
            var options = bulkOptions(stacks);
            var policy = options.storagePolicy(previous);
            assertEquals(stacks, policy.looseSlots());
            assertEquals(stacks * 64, StoragePackingPlanner.looseLimit(policy, 54, 64));
            assertEquals(Math.min(27, stacks) * 16,
                    StoragePackingPlanner.looseLimit(policy, 27, 16));
            assertEquals(previous.refillLoose(), policy.refillLoose());
            assertEquals(previous.allowPartialBoxes(), policy.allowPartialBoxes());
        }
        assertEquals(previous, bulkOptions(null).storagePolicy(previous));
        assertThrows(IllegalArgumentException.class, () -> bulkOptions(-1));
        assertThrows(IllegalArgumentException.class, () -> bulkOptions(55));
    }

    private static CategoryZoneOptions bulkOptions(Integer stacks) {
        return new CategoryZoneOptions("selection", "marker", "mixed", "inside-box", "",
                false, "", null, null, stacks);
    }

    @Test
    void standingSwitchControlsExecutionAndPreservesTheDraft() {
        var position = new BlockPosition(-8, 65, 12);
        var enabled = options(true, position);
        var standing = new AreaBounds(-8, 66, 12, -8, 66, 12);
        assertEquals(standing, enabled.standingArea(null));
        assertEquals(position, enabled.standingPosition());
        var disabled = options(false, position);
        assertNull(disabled.standingArea(standing));
        assertEquals(position, disabled.standingPosition());
        assertEquals(standing, new CategoryZoneOptions("single", "marker", "bulk",
                "inside-box", "", false, "").standingArea(standing));
        assertThrows(IllegalArgumentException.class, () -> options(true, null));
        assertThrows(IllegalArgumentException.class,
                () -> options(true, new BlockPosition(0, Integer.MAX_VALUE, 0)));
    }

    @Test
    void singleSliceExecutionShiftsStandingPointsAndRetainsTheOriginalDraft() {
        var options = options(true, new BlockPosition(0, 65, 1));
        var point = new AreaBounds(0, 64, 0, 0, 64, 0);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(), point, point,
                "DIRECT_BLOCK_ID", List.of(), List.of(), options.standingArea(null),
                StoragePolicy.DEFAULT, false, "A", true, options);
        var expansion = new CategoryZoneExpansionDefinition(new AreaBounds(0, 64, 0, 2, 64, 0),
                CategoryZoneExpansionAxis.X_POSITIVE, 1, 1);
        var expanded = CategoryZoneTiler.expand(expansion, List.of(binding), options);
        assertEquals(3, expanded.size());
        assertEquals(new AreaBounds(2, 66, 1, 2, 66, 1), expanded.get(2).standingArea());
        assertEquals(options, expanded.get(2).categoryOptions());
    }

    @Test
    void legacyStandingAreasRetainTheirFeetCoordinatesWithoutTheNewOption() {
        var point = new AreaBounds(0, 64, 0, 0, 64, 0);
        var standing = new AreaBounds(1, 65, 2, 2, 65, 2);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(), point, point,
                "DIRECT_BLOCK_ID", List.of(), List.of(), standing);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Legacy", List.of(binding),
                null, options(null, null));
        assertEquals(binding, zone.bindings().getFirst());
    }

    private static CategoryZoneOptions options(Boolean enabled, BlockPosition position) {
        return new CategoryZoneOptions("single", "marker", "bulk", "inside-box", "",
                false, "", enabled, position);
    }
}
