package dev.stowling.client.automation;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.NavigationTargetType;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import java.util.Objects;

public final class ClientThreadNavigationPort implements NavigationPort {
    private final NavigationPort delegate;
    private final ClientActionScheduler scheduler;

    public ClientThreadNavigationPort(
            NavigationPort delegate,
            ClientActionScheduler scheduler
    ) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public NavigationStatus start(BlockPosition target) {
        return start(target, NavigationTargetType.STANDARD);
    }

    @Override
    public NavigationStatus start(
            BlockPosition target,
            NavigationTargetType targetType
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetType, "targetType");
        if (!scheduler.isClientThread()) {
            return NavigationStatus.FAILED;
        }
        return delegate.start(target, targetType);
    }

    @Override
    public NavigationStatus status(BlockPosition target) {
        return status(target, NavigationTargetType.STANDARD);
    }

    @Override
    public NavigationStatus status(
            BlockPosition target,
            NavigationTargetType targetType
    ) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetType, "targetType");
        if (!scheduler.isClientThread()) {
            return NavigationStatus.FAILED;
        }
        return delegate.status(target, targetType);
    }

    @Override
    public void cancel() {
        if (scheduler.isClientThread()) {
            delegate.cancel();
        }
    }
}
