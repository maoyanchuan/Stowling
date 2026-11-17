package dev.stowling.navigation;

import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalComposite;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.ArrayList;
import java.util.List;
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
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(
                nearContainerGoal(target, nearGoalRadius(radius))
        );
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
    public void navigateToStandingArea(BlockPosition target, AreaBounds standingArea) {
        LocalPlayer player = primaryBaritone().getPlayerContext().player();
        if (player == null) {
            throw new IllegalStateException("当前没有可寻路的玩家");
        }
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(standingGoal(
                target, standingArea, player.blockInteractionRange(), player.getEyeHeight()
        ));
    }

    static Goal standingGoal(
            BlockPosition target,
            AreaBounds area,
            double reach,
            double eyeHeight
    ) {
        long margin = (long) Math.ceil(reach + eyeHeight + 1);
        List<Goal> goals = new ArrayList<>();
        for (long x = Math.max(area.minX(), (long) target.x() - margin);
                x <= Math.min(area.maxX(), (long) target.x() + margin); x++) {
            for (long y = Math.max(area.minY(), (long) target.y() - margin);
                    y <= Math.min(area.maxY(), (long) target.y() + margin); y++) {
                for (long z = Math.max(area.minZ(), (long) target.z() - margin);
                        z <= Math.min(area.maxZ(), (long) target.z() + margin); z++) {
                    // Half-block support is possible on stairs; verify actual reach on arrival.
                    if (withinReach(target, x + 0.5, y + eyeHeight, z + 0.5, reach)
                            || withinReach(target, x + 0.5, y + eyeHeight + 0.5,
                                    z + 0.5, reach)) {
                        goals.add(new GoalBlock((int) x, (int) y, (int) z));
                    }
                }
            }
        }
        if (goals.isEmpty()) {
            throw new IllegalArgumentException("操作站位区域内没有够得着目标容器的位置");
        }
        return new GoalComposite(goals.toArray(Goal[]::new));
    }

    private static boolean withinReach(
            BlockPosition target, double x, double y, double z, double reach
    ) {
        double dx = Math.max(Math.max(target.x() - x, 0), x - (target.x() + 1.0));
        double dy = Math.max(Math.max(target.y() - y, 0), y - (target.y() + 1.0));
        double dz = Math.max(Math.max(target.z() - z, 0), z - (target.z() + 1.0));
        return dx * dx + dy * dy + dz * dz < reach * reach;
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

    static Goal nearContainerGoal(
            BlockPosition target,
            int radius
    ) {
        BlockPos targetPos = new BlockPos(
                target.x(),
                target.y(),
                target.z()
        );
        // GoalNear accepts any reachable block in the interaction radius.
        return new GoalNear(targetPos, radius);
    }

    static int nearGoalRadius(
            int requestedRadius,
            double interactionRange
    ) {
        int safeRadius = Math.max(
                1,
                (int) Math.floor(interactionRange)
        );
        return Math.max(1, Math.min(requestedRadius, safeRadius));
    }
}
