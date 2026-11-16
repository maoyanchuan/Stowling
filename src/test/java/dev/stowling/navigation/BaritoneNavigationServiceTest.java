package dev.stowling.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import dev.stowling.domain.BlockPosition;
import org.junit.jupiter.api.Test;

class BaritoneNavigationServiceTest {
    @Test
    void coordinateRadiusZeroUsesExactBlockGoal() {
        BlockPosition target = new BlockPosition(7, -40, 1);

        Goal goal = BaritoneNavigationService.coordinateGoal(target, 0);

        assertTrue(goal instanceof GoalBlock);
        assertTrue(goal.isInGoal(7, -40, 1));
        assertFalse(goal.isInGoal(8, -40, 1));
        assertFalse(goal.isInGoal(7, -41, 1));
    }

    @Test
    void coordinateRadiusOneUsesActualThreeDimensionalGoalNear() {
        BlockPosition target = new BlockPosition(7, -40, 1);

        Goal goal = BaritoneNavigationService.coordinateGoal(target, 1);

        assertTrue(goal instanceof GoalNear);
        assertTrue(goal.isInGoal(8, -40, 1));
        assertTrue(goal.isInGoal(7, -41, 1));
        assertFalse(goal.isInGoal(8, -39, 1));
        assertFalse(goal.isInGoal(7, -38, 1));
    }

    @Test
    void coordinateRadiusLargerThanOneIsNotExpandedOrTwoBlocks() {
        BlockPosition target = new BlockPosition(7, -40, 1);

        Goal goal = BaritoneNavigationService.coordinateGoal(target, 3);

        assertTrue(goal instanceof GoalNear);
        assertTrue(goal.isInGoal(9, -38, 2));
        assertFalse(goal.isInGoal(9, -38, 3));
        assertFalse(goal.isInGoal(7, -44, 1));
        assertEquals(goal, new GoalNear(
                new net.minecraft.core.BlockPos(7, -40, 1),
                3
        ));
    }

    @Test
    void nearContainerGoalAcceptsVerticalAndRadiusApproaches() {
        BlockPosition target = new BlockPosition(7, -40, 1);
        var goal = BaritoneNavigationService.nearContainerGoal(target, 4);

        assertTrue(goal.isInGoal(7, -41, 1));
        assertTrue(goal.isInGoal(7, -44, 1));
        assertTrue(goal.isInGoal(8, -40, 1));
        assertFalse(goal.isInGoal(20, -40, 1));
    }

    @Test
    void nearGoalUsesGlobalInteractionRangeWithoutShrinkingTaskRadius() {
        assertEquals(5, BaritoneNavigationService.nearGoalRadius(4, 4.5D));
        assertEquals(7, BaritoneNavigationService.nearGoalRadius(7, 4.5D));
    }
}
