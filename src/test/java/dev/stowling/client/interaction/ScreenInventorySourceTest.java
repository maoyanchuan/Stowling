package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventoryFreshness;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ScreenInventorySourceTest {
    private static final ContainerRef CONTAINER = new ContainerRef(
            "storage",
            "minecraft:overworld",
            new BlockPosition(1, 64, 2),
            ContainerType.CHEST
    );

    @Test
    void missingOpenedScreenFailsInsteadOfWritingUnknownInventory() {
        ScreenInventorySource source = new ScreenInventorySource(
                new ImmediateScheduler(),
                ignored -> Optional.empty()
        );

        CompletableFuture<InventoryCapture> capture = source.capture(CONTAINER);

        CompletionException exception = assertThrows(
                CompletionException.class,
                capture::join
        );
        assertTrue(exception.getCause() instanceof IllegalStateException);
    }

    @Test
    void openedEmptyContainerProducesConfirmedFreshSnapshot() {
        ScreenInventorySource source = new ScreenInventorySource(
                new ImmediateScheduler(),
                ignored -> Optional.of(List.of())
        );

        InventoryCapture capture = source.capture(CONTAINER).join();

        assertEquals(InventoryFreshness.FRESH, capture.snapshot().freshness());
        assertTrue(capture.snapshot().items().isEmpty());
    }

    private static final class ImmediateScheduler implements ClientActionScheduler {
        @Override
        public boolean isClientThread() {
            return true;
        }

        @Override
        public void execute(Runnable action) {
            action.run();
        }

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> action) {
            try {
                return CompletableFuture.completedFuture(action.get());
            } catch (RuntimeException exception) {
                return CompletableFuture.failedFuture(exception);
            }
        }
    }
}
