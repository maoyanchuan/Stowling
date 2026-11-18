package dev.stowling.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import org.junit.jupiter.api.Test;

class BaritoneNavigationServiceTest {
    @Test
    void standingGoalRequiresTheSpecifiedStepAndExcludesTheSpaceBelowTheChest() {
        Goal goal = BaritoneNavigationService.interactionGoal(new BlockPosition(0, 68, 0),
                new AreaBounds(1, 64, 0, 2, 64, 0), 4.5, 1.62);

        assertTrue(goal.isInGoal(1, 64, 0));
        assertTrue(goal.isInGoal(2, 64, 0));
        assertFalse(goal.isInGoal(0, 64, 0));
        assertFalse(goal.isInGoal(1, 60, 0));
    }

    @Test
    void standingGoalRejectsAnAreaThatCannotReachTheContainer() {
        assertThrows(IllegalArgumentException.class,
                () -> BaritoneNavigationService.interactionGoal(new BlockPosition(0, 68, 0),
                        new AreaBounds(1, 58, 0, 1, 58, 0), 4.5, 1.62));
    }

    @Test
    void interactionGoalAcceptsReachableStanceOutsideOldRadius() {
        BlockPosition chest = new BlockPosition(5, -43, 32);
        Goal interaction = BaritoneNavigationService.interactionGoal(chest, null, 4.5, 1.62);
        Goal oldNearGoal = BaritoneNavigationService.nearContainerGoal(chest, 2);
        Goal reducedReach = BaritoneNavigationService.interactionGoal(chest, null, 1.5, 1.62);

        assertFalse(oldNearGoal.isInGoal(6, -45, 34));
        assertTrue(interaction.isInGoal(6, -45, 34));
        assertFalse(interaction.isInGoal(6, -51, 34));
        assertFalse(reducedReach.isInGoal(6, -45, 34));
    }

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

        assertTrue(goal.isInGoal(8, -40, 1));
        assertTrue(goal.isInGoal(7, -41, 1));
        assertTrue(goal.isInGoal(7, -44, 1));
        assertFalse(goal.isInGoal(20, -40, 1));
    }

    @Test
    void nearGoalStaysInsideInteractionRange() {
        assertEquals(4, BaritoneNavigationService.nearGoalRadius(4, 4.5D));
        assertEquals(4, BaritoneNavigationService.nearGoalRadius(7, 4.5D));
        assertEquals(2, BaritoneNavigationService.nearGoalRadius(2, 4.5D));
        assertEquals(1, BaritoneNavigationService.nearGoalRadius(4, 1.5D));
        assertEquals(4, BaritoneNavigationService.nearGoalRadius(4, 8.0D));
    }
}
