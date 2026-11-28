package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.model.AreaBounds;
import java.util.Objects;

public interface NavigationPort {
    NavigationStatus start(BlockPosition target);

    default NavigationStatus startHorizontal(BlockPosition target) {
        return start(target);
    }

    default NavigationStatus startNear(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        return start(target);
    }

    default NavigationStatus startWithinRadius(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        return start(target);
    }

    default NavigationStatus startInteraction(BlockPosition target, AreaBounds standingArea) {
        return NavigationStatus.FAILED;
    }

    default NavigationStatus startInteraction(BlockPosition target, AreaBounds standingArea,
            boolean avoidPickups) {
        return startInteraction(target, standingArea);
    }

    default boolean isWithinInteractionRange(BlockPosition target, AreaBounds standingArea) {
        return standingArea == null && isWithinInteractionRange(target);
    }

    default boolean isWithinInteractionRange(BlockPosition target, AreaBounds standingArea,
            boolean avoidPickups) {
        return isWithinInteractionRange(target, standingArea);
    }

    default NavigationStatus startInteraction(BlockPosition target, AreaBounds standingArea,
            boolean avoidPickups, boolean standingRangeEnabled) {
        return startInteraction(target, standingArea, avoidPickups);
    }

    default boolean isWithinInteractionRange(BlockPosition target, AreaBounds standingArea,
            boolean avoidPickups, boolean standingRangeEnabled) {
        return isWithinInteractionRange(target, standingArea, avoidPickups);
    }

    NavigationStatus status(BlockPosition target);

    default NavigationStatus status(BlockPosition target, Integer radius) {
        return status(target);
    }

    default boolean isInDimension(String dimension) {
        return true;
    }

    default boolean isWithinInteractionRange(BlockPosition target) {
        Objects.requireNonNull(target, "target");
        return false;
    }

    void cancel();

    default StopOutcome requestStop() {
        cancel();
        return StopOutcome.UNSAFE;
    }

    default StopOutcome pollStop() {
        return StopOutcome.UNSAFE;
    }
}
