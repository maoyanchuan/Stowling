package dev.stowling.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StandingRangeGoalTest {
    @Test
    void singleConfiguredSupportOffersBothBaritoneFeetHeightsAndNoOtherColumns() {
        var target = new BlockPosition(216, -23, -51);
        var area = new AreaBounds(220, -24, -51, 220, -24, -51);
        var goal = BaritoneNavigationService.standingRangeGoal(target, area, 4.5, 1.62,
                Set.of(), feet -> true).orElseThrow();
        assertTrue(goal.isInGoal(220, -25, -51));
        assertTrue(goal.isInGoal(220, -24, -51));
        assertFalse(goal.isInGoal(220, -26, -51));
        assertFalse(goal.isInGoal(220, -23, -51));
        assertFalse(goal.isInGoal(219, -24, -51));
        assertTrue(BaritoneNavigationService.standingRangeGoal(target, area, 2, 1.62,
                Set.of(), feet -> true).isEmpty());
        assertTrue(BaritoneNavigationService.standingRangeGoal(target, area, 4.5, 1.62,
                Set.of(), feet -> false).isEmpty());
    }
}
