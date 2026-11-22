package dev.stowling.navigation;

import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalXZ;
import baritone.api.pathing.goals.GoalComposite;
import dev.stowling.domain.BlockPosition;
import dev.stowling.client.automation.MinecraftPickupSafety;
import dev.stowling.v2.model.AreaBounds;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
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
    public void navigateToHorizontal(BlockPosition target) {
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(
                new GoalXZ(target.x(), target.z()));
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
        ) && MinecraftPickupSafety.safe(player.getBoundingBox(),
                MinecraftPickupSafety.hazards(player, target));
    }

    @Override
    public boolean navigateToInteractionRange(BlockPosition target, AreaBounds standingArea,
            Set<BlockPosition> rejectedStances) {
        IBaritone baritone = primaryBaritone();
        LocalPlayer player = baritone.getPlayerContext().player();
        if (player == null) {
            return false;
        }
        var hazards = MinecraftPickupSafety.hazards(player, target);
        Optional<Goal> goal = interactionGoal(target, standingArea, player.blockInteractionRange(),
                player.getEyeHeight(), rejectedStances, feet -> MinecraftPickupSafety.safe(
                        MinecraftPickupSafety.bodyAt(player, feet), hazards));
        goal.ifPresent(baritone.getCustomGoalProcess()::setGoalAndPath);
        return goal.isPresent();
    }

    @Override
    public Optional<BlockPosition> interactionFeet() {
        var context = primaryBaritone().getPlayerContext();
        if (context.player() == null) {
            return Optional.empty();
        }
        BlockPos feet = context.playerFeet();
        return Optional.of(new BlockPosition(feet.getX(), feet.getY(), feet.getZ()));
    }

    static Optional<Goal> interactionGoal(
            BlockPosition target,
            AreaBounds area,
            double reach,
            double eyeHeight,
            Set<BlockPosition> rejectedStances
    ) {
        return interactionGoal(target, area, reach, eyeHeight, rejectedStances, feet -> true);
    }

    static Optional<Goal> interactionGoal(
            BlockPosition target, AreaBounds area, double reach, double eyeHeight,
            Set<BlockPosition> rejectedStances, Predicate<BlockPosition> safeStance
    ) {
        if (area == null) {
            area = new AreaBounds(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE,
                    Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE);
        }
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
                        BlockPosition stance = new BlockPosition((int) x, (int) y, (int) z);
                        if (!rejectedStances.contains(stance) && safeStance.test(stance)) {
                            goals.add(new GoalBlock(stance.x(), stance.y(), stance.z()));
                        }
                    }
                }
            }
        }
        return goals.isEmpty() ? Optional.empty()
                : Optional.of(new GoalComposite(goals.toArray(Goal[]::new)));
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
