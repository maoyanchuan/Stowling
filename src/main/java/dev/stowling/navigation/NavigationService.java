package dev.stowling.navigation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public interface NavigationService {
    void navigateTo(BlockPosition target);

    default void navigateToHorizontal(BlockPosition target) {
        navigateTo(target);
    }

    void navigateToNear(BlockPosition target, int radius);

    default boolean navigateToInteractionRange(BlockPosition target, AreaBounds standingArea,
            Set<BlockPosition> rejectedStances) {
        return false;
    }

    default boolean navigateToInteractionRange(BlockPosition target, AreaBounds standingArea,
            Set<BlockPosition> rejectedStances, boolean avoidPickups) {
        return navigateToInteractionRange(target, standingArea, rejectedStances);
    }

    default Optional<BlockPosition> interactionFeet() {
        return Optional.empty();
    }

    default boolean navigateToStandingRange(BlockPosition target, AreaBounds area,
            Set<BlockPosition> rejectedStances, boolean avoidPickups) {
        return false;
    }

    default boolean isWithinStandingRange(AreaBounds area) {
        return false;
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

    default boolean isWithinInteractionRange(BlockPosition target, boolean avoidPickups) {
        return isWithinInteractionRange(target);
    }

    default int nearGoalRadius(int requestedRadius) {
        return requestedRadius;
    }

    void cancel();

    boolean isNavigatingOrCalculating();
}
