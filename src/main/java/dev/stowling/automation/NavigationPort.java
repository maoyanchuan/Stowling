package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import java.util.Objects;

public interface NavigationPort {
    NavigationStatus start(BlockPosition target);

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

    NavigationStatus status(BlockPosition target);

    default NavigationStatus status(BlockPosition target, Integer radius) {
        return status(target);
    }

    default boolean isInDimension(String dimension) {
        return true;
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
