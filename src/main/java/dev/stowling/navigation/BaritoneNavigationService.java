package dev.stowling.navigation;

import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.utils.IPlayerContext;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalNear;
import dev.stowling.Stowling;
import dev.stowling.domain.BlockPosition;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

public final class BaritoneNavigationService implements NavigationService {
    private static final int MAX_APPROACH_CANDIDATES = 12;

    private final IBaritoneProvider provider;
    private final Deque<BlockPosition> nearbyTargets = new ArrayDeque<>();

    public BaritoneNavigationService(IBaritoneProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public void navigateTo(BlockPosition target) {
        Objects.requireNonNull(target, "target");
        nearbyTargets.clear();
        GoalBlock goal = new GoalBlock(target.x(), target.y(), target.z());
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(goal);
    }

    @Override
    public void navigateToNear(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        List<BlockPosition> candidates = nearbyStandPositions(target, radius);
        nearbyTargets.clear();
        nearbyTargets.addAll(candidates);
        if (!nearbyTargets.isEmpty()) {
            BlockPosition first = nearbyTargets.removeFirst();
            Stowling.LOGGER.info(
                    "Container approach found {} stand positions; trying {}",
                    candidates.size(),
                    first
            );
            navigateToExact(first);
            return;
        }
        GoalNear goal = new GoalNear(
                new BlockPos(target.x(), target.y(), target.z()),
                radius
        );
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(goal);
    }

    @Override
    public boolean navigateToNextNear() {
        BlockPosition next = nearbyTargets.pollFirst();
        if (next == null) {
            return false;
        }
        Stowling.LOGGER.info("Trying next container approach position {}", next);
        navigateToExact(next);
        return true;
    }

    @Override
    public void cancel() {
        nearbyTargets.clear();
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

    private void navigateToExact(BlockPosition target) {
        GoalBlock goal = new GoalBlock(target.x(), target.y(), target.z());
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(goal);
    }

    private List<BlockPosition> nearbyStandPositions(
            BlockPosition target,
            int radius
    ) {
        IPlayerContext context = primaryBaritone().getPlayerContext();
        LocalPlayer player = context.player();
        Level world = context.world();
        if (player == null || world == null) {
            return List.of();
        }
        BlockPos targetPos = new BlockPos(target.x(), target.y(), target.z());
        Vec3 targetCenter = Vec3.atCenterOf(targetPos);
        double interactionRangeSquared = Math.pow(
                player.blockInteractionRange(),
                2
        );
        int searchRadius = Math.max(1, radius);
        List<ApproachCandidate> candidates = new ArrayList<>();
        for (int deltaX = -searchRadius; deltaX <= searchRadius; deltaX++) {
            for (int deltaY = -2; deltaY <= 2; deltaY++) {
                for (int deltaZ = -searchRadius; deltaZ <= searchRadius; deltaZ++) {
                    BlockPos feet = targetPos.offset(deltaX, deltaY, deltaZ);
                    if (!isWithinTargetRange(
                            targetPos,
                            feet,
                            radius
                    ) || !isStandable(world, feet)) {
                        continue;
                    }
                    Vec3 eye = new Vec3(
                            feet.getX() + 0.5D,
                            feet.getY() + player.getEyeHeight(),
                            feet.getZ() + 0.5D
                    );
                    if (eye.distanceToSqr(targetCenter)
                            > interactionRangeSquared) {
                        continue;
                    }
                    candidates.add(new ApproachCandidate(
                            toBlockPosition(feet),
                            Vec3.atCenterOf(feet).distanceToSqr(targetCenter),
                            Vec3.atCenterOf(feet).distanceToSqr(
                                    player.position()
                            )
                    ));
                }
            }
        }
        return candidates.stream()
                .sorted(Comparator
                        .comparingDouble(ApproachCandidate::targetDistanceSquared)
                        .thenComparingDouble(
                                ApproachCandidate::playerDistanceSquared
                        ))
                .limit(MAX_APPROACH_CANDIDATES)
                .map(ApproachCandidate::position)
                .toList();
    }

    private static boolean isWithinTargetRange(
            BlockPos target,
            BlockPos candidate,
            int radius
    ) {
        int deltaX = candidate.getX() - target.getX();
        int deltaY = candidate.getY() - target.getY();
        int deltaZ = candidate.getZ() - target.getZ();
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ
                <= radius * radius;
    }

    private static boolean isStandable(Level world, BlockPos feet) {
        BlockPos head = feet.above();
        BlockPos floor = feet.below();
        if (!world.isLoaded(feet)
                || !world.isLoaded(head)
                || !world.isLoaded(floor)) {
            return false;
        }
        BlockState feetState = world.getBlockState(feet);
        BlockState headState = world.getBlockState(head);
        BlockState floorState = world.getBlockState(floor);
        return feetState.getCollisionShape(world, feet).isEmpty()
                && headState.getCollisionShape(world, head).isEmpty()
                && !floorState.getCollisionShape(world, floor).isEmpty()
                && feetState.getFluidState().isEmpty();
    }

    private static BlockPosition toBlockPosition(BlockPos position) {
        return new BlockPosition(
                position.getX(),
                position.getY(),
                position.getZ()
        );
    }

    private record ApproachCandidate(
            BlockPosition position,
            double targetDistanceSquared,
            double playerDistanceSquared
    ) {
    }
}
