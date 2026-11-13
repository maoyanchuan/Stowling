package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.automation.ContainerAttempt;
import dev.stowling.automation.ContainerAttemptResult;
import dev.stowling.automation.ContainerAttemptStatus;
import dev.stowling.automation.ContainerInteraction;
import dev.stowling.automation.ContainerTransfer;
import dev.stowling.automation.ContainerView;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ClientThreadContainerInteractionTest {
    private final ContainerRef container = new ContainerRef(
            "cache",
            "minecraft:overworld",
            new BlockPosition(0, 64, 0),
            ContainerType.CHEST
    );

    @Test
    void delegatesWhenRunningOnClientThread() {
        RecordingInteraction delegate = new RecordingInteraction();
        ClientThreadContainerInteraction interaction = new ClientThreadContainerInteraction(
                new ImmediateScheduler(true),
                delegate
        );

        assertEquals(Optional.empty(), interaction.observe(container));
        assertEquals(1, delegate.observations);
    }

    @Test
    void rejectsContainerCallsFromBackgroundThread() {
        ClientThreadContainerInteraction interaction = new ClientThreadContainerInteraction(
                new ImmediateScheduler(false),
                new RecordingInteraction()
        );

        assertThrows(IllegalStateException.class, () -> interaction.observe(container));
    }

    private static final class ImmediateScheduler implements ClientActionScheduler {
        private final boolean clientThread;

        private ImmediateScheduler(boolean clientThread) {
            this.clientThread = clientThread;
        }

        @Override
        public boolean isClientThread() {
            return clientThread;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> action) {
            return CompletableFuture.completedFuture(action.get());
        }
    }

    private static final class RecordingInteraction implements ContainerInteraction {
        private int observations;

        @Override
        public Optional<ContainerView> observe(ContainerRef ignored) {
            observations++;
            return Optional.empty();
        }

        @Override
        public ContainerAttempt begin(ContainerTransfer transfer, long baselineRevision) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ContainerAttemptResult poll(ContainerAttempt attempt) {
            return new ContainerAttemptResult(
                    ContainerAttemptStatus.PENDING,
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    "pending"
            );
        }

        @Override
        public void cancel(ContainerAttempt attempt) {
            // No pending transaction is retained by this fake.
        }
    }
}
