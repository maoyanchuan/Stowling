package dev.stowling.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import dev.stowling.domain.BlockPosition;
import dev.stowling.client.automation.MinecraftPickupSafety;
import dev.stowling.v2.model.AreaBounds;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

class BaritoneNavigationServiceTest {
    @Test
    void storageLeavesThePickupPointEvenWhenItAlreadyReachesTheCache() {
        BlockPosition chest = new BlockPosition(5, -43, 32);
        var drops = List.of(new AABB(5.3, -44, 34.3, 5.55, -43.75, 34.55));
        Goal goal = BaritoneNavigationService.interactionGoal(chest, null, 4.5, 1.62,
                Set.of(), feet -> MinecraftPickupSafety.safe(new AABB(feet.x() + 0.2,
                        feet.y(), feet.z() + 0.2, feet.x() + 0.8, feet.y() + 1.8,
                        feet.z() + 0.8), drops)).orElseThrow();

        assertFalse(goal.isInGoal(5, -44, 34));
        assertTrue(goal.isInGoal(5, -44, 31));
    }

    @Test
    void configuredStandingAreaCannotFallBackToAnUnsafePickupPoint() {
        assertTrue(BaritoneNavigationService.interactionGoal(new BlockPosition(5, -43, 32),
                new AreaBounds(5, -44, 34, 5, -44, 34), 4.5, 1.62, Set.of(),
                feet -> false).isEmpty());
    }

    @Test
    void standingGoalRequiresTheSpecifiedStepAndExcludesTheSpaceBelowTheChest() {
        Goal goal = BaritoneNavigationService.interactionGoal(new BlockPosition(0, 68, 0),
                new AreaBounds(1, 64, 0, 2, 64, 0), 4.5, 1.62, Set.of()).orElseThrow();

        assertTrue(goal.isInGoal(1, 64, 0));
        assertTrue(goal.isInGoal(2, 64, 0));
        assertFalse(goal.isInGoal(0, 64, 0));
        assertFalse(goal.isInGoal(1, 60, 0));
    }

    @Test
    void standingGoalRejectsAnAreaThatCannotReachTheContainer() {
        assertTrue(BaritoneNavigationService.interactionGoal(new BlockPosition(0, 68, 0),
                new AreaBounds(1, 58, 0, 1, 58, 0), 4.5, 1.62, Set.of()).isEmpty());
    }

    @Test
    void interactionGoalAcceptsReachableStanceOutsideOldRadius() {
        BlockPosition chest = new BlockPosition(5, -43, 32);
        Goal interaction = BaritoneNavigationService.interactionGoal(
                chest, null, 4.5, 1.62, Set.of()).orElseThrow();
        Goal oldNearGoal = BaritoneNavigationService.nearContainerGoal(chest, 2);
        Goal reducedReach = BaritoneNavigationService.interactionGoal(
                chest, null, 1.5, 1.62, Set.of()).orElseThrow();

        assertFalse(oldNearGoal.isInGoal(6, -45, 34));
        assertTrue(interaction.isInGoal(6, -45, 34));
        assertFalse(interaction.isInGoal(6, -51, 34));
        assertFalse(reducedReach.isInGoal(6, -45, 34));
    }

    @Test
    void excludesTheObservedStallButRetainsTheUsableStance() {
        BlockPosition chest = new BlockPosition(5, -43, 32);
        BlockPosition stalled = new BlockPosition(8, -45, 36);
        Goal unfiltered = BaritoneNavigationService.interactionGoal(
                chest, null, 4.5, 1.62, Set.of()).orElseThrow();
        Goal alternative = BaritoneNavigationService.interactionGoal(
                chest, null, 4.5, 1.62, Set.of(stalled)).orElseThrow();

        assertTrue(unfiltered.isInGoal(stalled.x(), stalled.y(), stalled.z()));
        assertFalse(alternative.isInGoal(stalled.x(), stalled.y(), stalled.z()));
        assertTrue(alternative.isInGoal(6, -45, 34));
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
