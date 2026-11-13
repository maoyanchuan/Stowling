package dev.stowling.client;

import dev.stowling.Stowling;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class ClientShutdownSequence implements AutoCloseable {
    private final List<ShutdownStep> steps;
    private final AtomicBoolean closed = new AtomicBoolean();

    ClientShutdownSequence(List<ShutdownStep> steps) {
        this.steps = List.copyOf(Objects.requireNonNull(steps, "steps"));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ShutdownStep step : steps) {
            try {
                step.action().run();
            } catch (RuntimeException | LinkageError exception) {
                Stowling.LOGGER.warn(
                        "Stowling {} cleanup failed during client shutdown ({})",
                        step.name(),
                        exception.getClass().getSimpleName()
                );
            }
        }
    }

    record ShutdownStep(String name, Runnable action) {
        ShutdownStep {
            name = Objects.requireNonNull(name, "name");
            action = Objects.requireNonNull(action, "action");
        }
    }
}
