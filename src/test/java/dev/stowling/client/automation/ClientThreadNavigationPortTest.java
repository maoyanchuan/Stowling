package dev.stowling.client.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.domain.BlockPosition;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class ClientThreadNavigationPortTest {
    @Test
    void rangePolicyIsForwardedOnlyOnTheClientThread() {
        var target = new BlockPosition(216, -23, -51);
        var area = new dev.stowling.v2.model.AreaBounds(220, -24, -51, 220, -24, -51);
        var delegate = new RecordingNavigationPort();
        var offThread = new ClientThreadNavigationPort(delegate, new TestScheduler(false));
        assertEquals(NavigationStatus.FAILED,
                offThread.startInteraction(target, area, true, true));
        assertEquals(false, offThread.isWithinInteractionRange(target, area, true, true));
        assertEquals(0, delegate.rangeCalls);
        var onThread = new ClientThreadNavigationPort(delegate, new TestScheduler(true));
        assertEquals(NavigationStatus.NAVIGATING,
                onThread.startInteraction(target, area, true, true));
        assertEquals(true, onThread.isWithinInteractionRange(target, area, true, true));
        assertEquals(2, delegate.rangeCalls);
    }

    @Test
    void forwardsWithinRadiusOnClientThread() {
        RecordingNavigationPort delegate = new RecordingNavigationPort();
        ClientThreadNavigationPort port = new ClientThreadNavigationPort(
                delegate,
                new TestScheduler(true)
        );
        BlockPosition target = new BlockPosition(3, 70, -2);

        NavigationStatus status = port.startWithinRadius(target, 7);

        assertEquals(NavigationStatus.NAVIGATING, status);
        assertEquals(target, delegate.coordinateTarget);
        assertEquals(7, delegate.coordinateRadius);
    }

    @Test
    void rejectsWithinRadiusOutsideClientThread() {
        RecordingNavigationPort delegate = new RecordingNavigationPort();
        ClientThreadNavigationPort port = new ClientThreadNavigationPort(
                delegate,
                new TestScheduler(false)
        );

        NavigationStatus status = port.startWithinRadius(
                new BlockPosition(3, 70, -2),
                7
        );

        assertEquals(NavigationStatus.FAILED, status);
        assertNull(delegate.coordinateTarget);
    }

    private static final class RecordingNavigationPort implements NavigationPort {
        private int rangeCalls;
        private BlockPosition coordinateTarget;
        private int coordinateRadius = -1;

        @Override
        public NavigationStatus startInteraction(BlockPosition target,
                dev.stowling.v2.model.AreaBounds area, boolean avoidPickups, boolean rangeEnabled) {
            assertEquals(true, rangeEnabled);
            rangeCalls++;
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public boolean isWithinInteractionRange(BlockPosition target,
                dev.stowling.v2.model.AreaBounds area, boolean avoidPickups, boolean rangeEnabled) {
            assertEquals(true, rangeEnabled);
            rangeCalls++;
            return true;
        }

        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus startWithinRadius(BlockPosition target, int radius) {
            coordinateTarget = target;
            coordinateRadius = radius;
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public void cancel() {
        }
    }

    private static final class TestScheduler implements ClientActionScheduler {
        private final boolean clientThread;

        private TestScheduler(boolean clientThread) {
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
}
