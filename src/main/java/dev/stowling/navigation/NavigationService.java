package dev.stowling.navigation;

import dev.stowling.domain.BlockPosition;

public interface NavigationService {
    void navigateTo(BlockPosition target);

    void cancel();

    boolean isNavigating();
}
