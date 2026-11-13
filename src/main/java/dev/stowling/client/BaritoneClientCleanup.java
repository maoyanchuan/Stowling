package dev.stowling.client;

import baritone.api.IBaritone;
import baritone.api.cache.IWorldData;
import dev.stowling.Stowling;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

final class BaritoneClientCleanup implements AutoCloseable {
    private static final Duration EXECUTOR_WAIT = Duration.ofSeconds(2);

    private final IBaritone primaryBaritone;
    private final AtomicBoolean closed = new AtomicBoolean();

    BaritoneClientCleanup(IBaritone primaryBaritone) {
        this.primaryBaritone = Objects.requireNonNull(primaryBaritone, "primaryBaritone");
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        runStep("pathing cancellation", this::cancelPathing);
        runStep("cached world save", this::saveCachedWorld);
        runStep("executor shutdown", this::shutdownExecutors);
    }

    private void cancelPathing() {
        primaryBaritone.getPathingBehavior().cancelEverything();
    }

    private void saveCachedWorld() {
        IWorldData world = primaryBaritone.getWorldProvider().getCurrentWorld();
        if (world != null && world.getCachedWorld() != null) {
            world.getCachedWorld().save();
        }
    }

    private void shutdownExecutors() {
        BaritoneExecutorShutdown.shutdown(
                primaryBaritone,
                IBaritone.class,
                EXECUTOR_WAIT
        );
    }

    private static void runStep(String step, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException | LinkageError exception) {
            Stowling.LOGGER.warn(
                    "Baritone {} failed during client shutdown ({})",
                    step,
                    exception.getClass().getSimpleName()
            );
        }
    }
}
