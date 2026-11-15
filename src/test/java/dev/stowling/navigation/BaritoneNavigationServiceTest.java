package dev.stowling.navigation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import org.junit.jupiter.api.Test;

class BaritoneNavigationServiceTest {
    @Test
    void nearContainerGoalAcceptsVerticalAndRadiusApproaches() {
        BlockPosition target = new BlockPosition(7, -40, 1);
        var goal = BaritoneNavigationService.nearContainerGoal(target, 4);

        assertTrue(goal.isInGoal(7, -41, 1));
        assertTrue(goal.isInGoal(7, -44, 1));
        assertTrue(goal.isInGoal(8, -40, 1));
        assertFalse(goal.isInGoal(20, -40, 1));
    }
}
