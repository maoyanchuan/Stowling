package dev.stowling.navigation;

import baritone.api.IBaritone;
import baritone.api.IBaritoneProvider;
import baritone.api.pathing.goals.GoalBlock;
import dev.stowling.domain.BlockPosition;
import java.util.Objects;

public final class BaritoneNavigationService implements NavigationService {
    private final IBaritoneProvider provider;

    public BaritoneNavigationService(IBaritoneProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    @Override
    public void navigateTo(BlockPosition target) {
        Objects.requireNonNull(target, "target");
        GoalBlock goal = new GoalBlock(target.x(), target.y(), target.z());
        primaryBaritone().getCustomGoalProcess().setGoalAndPath(goal);
    }

    @Override
    public void cancel() {
        primaryBaritone().getPathingBehavior().cancelEverything();
    }

    @Override
    public boolean isNavigating() {
        return primaryBaritone().getPathingBehavior().isPathing();
    }

    private IBaritone primaryBaritone() {
        return provider.getPrimaryBaritone();
    }
}
