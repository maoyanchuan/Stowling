package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;

public interface NavigationPort {
    NavigationStatus start(BlockPosition target);

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
