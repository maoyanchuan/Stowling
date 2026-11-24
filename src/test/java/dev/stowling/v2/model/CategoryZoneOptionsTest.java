package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.domain.BlockPosition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CategoryZoneOptionsTest {
    @Test
    void standingSwitchControlsExecutionAndPreservesTheDraft() {
        var position = new BlockPosition(-8, 65, 12);
        var enabled = options(true, position);
        var standing = new AreaBounds(-8, 65, 12, -8, 65, 12);
        assertEquals(standing, enabled.standingArea(null));
        var disabled = options(false, position);
        assertNull(disabled.standingArea(standing));
        assertEquals(position, disabled.standingPosition());
        assertEquals(standing, new CategoryZoneOptions("single", "marker", "bulk",
                "inside-box", "", false, "").standingArea(standing));
        assertThrows(IllegalArgumentException.class, () -> options(true, null));
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
        assertEquals(new AreaBounds(2, 65, 1, 2, 65, 1), expanded.get(2).standingArea());
        assertEquals(options, expanded.get(2).categoryOptions());
    }

    private static CategoryZoneOptions options(Boolean enabled, BlockPosition position) {
        return new CategoryZoneOptions("single", "marker", "bulk", "inside-box", "",
                false, "", enabled, position);
    }
}
