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

    default Optional<BlockPosition> interactionFeet() {
        return Optional.empty();
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
