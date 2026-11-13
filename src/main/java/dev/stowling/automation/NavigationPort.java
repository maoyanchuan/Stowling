package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;

public interface NavigationPort {
    NavigationStatus start(BlockPosition target);

    default NavigationStatus start(BlockPosition target, NavigationTargetType targetType) {
        return start(target);
    }

    NavigationStatus status(BlockPosition target);

    default NavigationStatus status(BlockPosition target, NavigationTargetType targetType) {
        return status(target);
    }

    void cancel();
}
