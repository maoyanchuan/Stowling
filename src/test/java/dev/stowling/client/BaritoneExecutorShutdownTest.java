package dev.stowling.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BaritoneExecutorShutdownTest {
    @Test
    void shutsDownOnlyStaticThreadPoolExecutorsInImplementationHierarchy() {
        FakeBaritone primary = new FakeBaritone();
        FakeBaritoneBase.OWNED.prestartCoreThread();
        primary.instanceExecutor.prestartCoreThread();

        try {
            BaritoneExecutorShutdown.shutdown(
                    primary,
                    FakeBaritoneApi.class,
                    Duration.ofMillis(200)
            );
            BaritoneExecutorShutdown.shutdown(
                    primary,
                    FakeBaritoneApi.class,
                    Duration.ofMillis(200)
            );

            assertTrue(FakeBaritoneBase.OWNED.isShutdown());
            assertTrue(FakeBaritoneBase.OWNED.isTerminated());
            assertFalse(primary.instanceExecutor.isShutdown());
            assertFalse(FakeBaritoneBase.NON_THREAD_POOL.isShutdown());
        } finally {
            primary.instanceExecutor.shutdownNow();
            FakeBaritoneBase.NON_THREAD_POOL.shutdownNow();
            FakeBaritoneBase.OWNED.shutdownNow();
        }
    }

    private interface FakeBaritoneApi {
    }

    private static class FakeBaritoneBase implements FakeBaritoneApi {
        private static final ThreadPoolExecutor OWNED = executor();
        private static final ExecutorService NON_THREAD_POOL =
                Executors.newSingleThreadExecutor(task -> daemonThread(task, "ignored-static"));
    }

    private static final class FakeBaritone extends FakeBaritoneBase {
        private final ThreadPoolExecutor instanceExecutor = executor();
    }

    private static ThreadPoolExecutor executor() {
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                task -> daemonThread(task, "fake-baritone")
        );
    }

    private static Thread daemonThread(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }
}
