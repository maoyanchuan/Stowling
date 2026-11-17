package dev.stowling.navigation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.Objects;

public interface NavigationService {
    void navigateTo(BlockPosition target);

    void navigateToNear(BlockPosition target, int radius);

    default void navigateToStandingArea(BlockPosition target, AreaBounds standingArea) {
        throw new IllegalStateException("当前寻路组件不支持操作站位区域");
    }

    default void navigateToWithinRadius(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        navigateTo(target);
    }

    default boolean isWithinInteractionRange(BlockPosition target) {
        return false;
    }

    default int nearGoalRadius(int requestedRadius) {
        return requestedRadius;
    }

    void cancel();

    boolean isNavigatingOrCalculating();
}
