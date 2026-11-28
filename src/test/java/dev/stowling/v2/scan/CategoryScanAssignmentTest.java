package dev.stowling.v2.scan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.List;
import org.junit.jupiter.api.Test;

class CategoryScanAssignmentTest {
    @Test
    void adjacentGroupsDoNotIndependentlyClaimTheSameDisplay() {
        var first = List.of(choice(0, 10), choice(1, 10));
        var second = List.of(choice(1, 10), choice(2, 10));
        var third = List.of(choice(2, 10));
        var result = CategoryScanAssignment.assign(List.of(first, second, third));
        assertEquals(List.of(0, 1, 2), result.choices().stream()
                .map(value -> value.anchor().x()).toList());
        assertTrue(result.ambiguous().isEmpty());
    }

    @Test
    void equallyGoodSwapsAreReportedAsAmbiguities() {
        var options = List.of(choice(0, 10), choice(1, 10));
        var result = CategoryScanAssignment.assign(List.of(options, options));
        assertEquals(java.util.Set.of(0, 1), result.ambiguous());
    }

    @Test
    void strongerCombinedEvidenceCanDisplaceAnEarlierAssignment() {
        var result = CategoryScanAssignment.assign(List.of(
                List.of(choice(0, 100), choice(1, 99)),
                List.of(choice(0, 100), choice(2, 1))));
        assertEquals(List.of(1, 0), result.choices().stream()
                .map(value -> value.anchor().x()).toList());
        assertTrue(result.ambiguous().isEmpty());
    }

    private static CategoryScanAssignment.Choice choice(int x, long score) {
        return new CategoryScanAssignment.Choice(new BlockPosition(x, 64, 0),
                new AreaBounds(x, 64, 0, x, 64, 0), score);
    }
}
