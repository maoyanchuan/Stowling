package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;

public interface NavigationPort {
    NavigationStatus start(BlockPosition target);

    NavigationStatus status(BlockPosition target);

    void cancel();
}
