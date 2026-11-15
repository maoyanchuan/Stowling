package dev.stowling.navigation;

import dev.stowling.domain.BlockPosition;

public interface NavigationService {
    void navigateTo(BlockPosition target);

    void navigateToNear(BlockPosition target, int radius);

    default boolean navigateToNextNear() {
        return false;
    }

    void cancel();

    boolean isNavigatingOrCalculating();
}
