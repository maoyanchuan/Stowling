package dev.stowling.client.automation;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
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
        Objects.requireNonNull(target, "target");
        if (!scheduler.isClientThread()) {
            return NavigationStatus.FAILED;
        }
        return delegate.start(target);
    }

    @Override
    public NavigationStatus startNear(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        if (!scheduler.isClientThread()) {
            return NavigationStatus.FAILED;
        }
        return delegate.startNear(target, radius);
    }

    @Override
    public NavigationStatus startWithinRadius(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        if (!scheduler.isClientThread()) {
            return NavigationStatus.FAILED;
        }
        return delegate.startWithinRadius(target, radius);
    }

    @Override
    public NavigationStatus status(BlockPosition target) {
        return status(target, null);
    }

    @Override
    public NavigationStatus status(BlockPosition target, Integer radius) {
        Objects.requireNonNull(target, "target");
        if (!scheduler.isClientThread()) {
            return NavigationStatus.FAILED;
        }
        return delegate.status(target, radius);
    }

    @Override
    public boolean isInDimension(String dimension) {
        Objects.requireNonNull(dimension, "dimension");
        return scheduler.isClientThread() && delegate.isInDimension(dimension);
    }

    @Override
    public boolean isWithinInteractionRange(BlockPosition target) {
        Objects.requireNonNull(target, "target");
        return scheduler.isClientThread()
                && delegate.isWithinInteractionRange(target);
    }

    @Override
    public void cancel() {
        if (scheduler.isClientThread()) {
            delegate.cancel();
        }
    }

    @Override
    public StopOutcome requestStop() {
        return scheduler.isClientThread()
                ? delegate.requestStop()
                : StopOutcome.UNSAFE;
    }

    @Override
    public StopOutcome pollStop() {
        return scheduler.isClientThread()
                ? delegate.pollStop()
                : StopOutcome.UNSAFE;
    }
}
