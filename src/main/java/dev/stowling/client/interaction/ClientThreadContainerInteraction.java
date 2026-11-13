package dev.stowling.client.interaction;

import dev.stowling.automation.ContainerAttempt;
import dev.stowling.automation.ContainerAttemptResult;
import dev.stowling.automation.ContainerInteraction;
import dev.stowling.automation.ContainerTransfer;
import dev.stowling.automation.ContainerView;
import dev.stowling.domain.ContainerRef;
import java.util.Objects;
import java.util.Optional;

public final class ClientThreadContainerInteraction implements ContainerInteraction {
    private final ClientActionScheduler scheduler;
    private final ContainerInteraction delegate;

    public ClientThreadContainerInteraction(
            ClientActionScheduler scheduler,
            ContainerInteraction delegate
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public Optional<ContainerView> observe(ContainerRef container) {
        requireClientThread();
        return delegate.observe(container);
    }

    @Override
    public ContainerAttempt begin(ContainerTransfer transfer, long baselineRevision) {
        requireClientThread();
        return delegate.begin(transfer, baselineRevision);
    }

    @Override
    public ContainerAttemptResult poll(ContainerAttempt attempt) {
        requireClientThread();
        return delegate.poll(attempt);
    }

    @Override
    public void cancel(ContainerAttempt attempt) {
        requireClientThread();
        delegate.cancel(attempt);
    }

    private void requireClientThread() {
        if (!scheduler.isClientThread()) {
            throw new IllegalStateException("container interaction must run on client thread");
        }
    }
}
