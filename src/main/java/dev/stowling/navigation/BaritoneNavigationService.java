package dev.stowling.navigation;

import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalComposite;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalTwoBlocks;
import dev.stowling.domain.BlockPosition;
import java.util.Objects;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;

public final class BaritoneNavigationService implements NavigationService {
    private final IBaritoneProvider provider;

    public BaritoneNavigationService(IBaritoneProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public void navigateTo(BlockPosition target) {
        navigateToWithinRadius(target, 0);
    }

    @Override
    public void navigateToWithinRadius(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(
                coordinateGoal(target, radius)
        );
    }

    @Override
    public void navigateToNear(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        GoalComposite goal = nearContainerGoal(
                target,
                nearGoalRadius(radius)
        );
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(goal);
    }

    @Override
    public boolean isWithinInteractionRange(BlockPosition target) {
        Objects.requireNonNull(target, "target");
        LocalPlayer player = primaryBaritone().getPlayerContext().player();
        return player != null && player.isWithinBlockInteractionRange(
                new BlockPos(target.x(), target.y(), target.z()),
                0.0D
        );
    }

    @Override
    public int nearGoalRadius(int requestedRadius) {
        LocalPlayer player = primaryBaritone().getPlayerContext().player();
        return player == null
                ? requestedRadius
                : nearGoalRadius(requestedRadius, player.blockInteractionRange());
    }

    @Override
    public void cancel() {
        primaryBaritone().getPathingBehavior().cancelEverything();
    }

    @Override
    public boolean isNavigatingOrCalculating() {
        IPathingBehavior pathing = primaryBaritone().getPathingBehavior();
        return pathing.isPathing()
                || pathing.hasPath()
                || pathing.getInProgress().isPresent();
    }

    private IBaritone primaryBaritone() {
        return provider.getPrimaryBaritone();
    }

    static Goal coordinateGoal(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        BlockPos targetPos = new BlockPos(
                target.x(),
                target.y(),
                target.z()
        );
        if (radius == 0) {
            return new GoalBlock(targetPos);
        }
        return new GoalNear(targetPos, radius);
    }

    static GoalComposite nearContainerGoal(
            BlockPosition target,
            int radius
    ) {
        BlockPos targetPos = new BlockPos(
                target.x(),
                target.y(),
                target.z()
        );
        return new GoalComposite(
                new GoalTwoBlocks(targetPos),
                new GoalNear(targetPos, radius)
        );
    }

    static int nearGoalRadius(
            int requestedRadius,
            double interactionRange
    ) {
        return Math.max(
                requestedRadius,
                (int) Math.ceil(interactionRange)
        );
    }
}
