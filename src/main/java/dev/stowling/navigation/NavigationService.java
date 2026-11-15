package dev.stowling.navigation;

import dev.stowling.domain.BlockPosition;

public interface NavigationService {
    void navigateTo(BlockPosition target);

    void navigateToNear(BlockPosition target, int radius);

    default boolean isWithinInteractionRange(BlockPosition target) {
        return false;
    }

    default int nearGoalRadius(int requestedRadius) {
        return requestedRadius;
    }

    void cancel();

    boolean isNavigatingOrCalculating();
}
