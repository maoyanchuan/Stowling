package dev.stowling.client;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

final class BaritoneExecutorShutdown {
    private BaritoneExecutorShutdown() {
    }

    static void shutdown(
            Object primaryBaritone,
            Class<?> baritoneApiType,
            Duration timeout
    ) {
        if (!baritoneApiType.isInstance(primaryBaritone)) {
            return;
        }
        Set<ThreadPoolExecutor> executors = Collections.newSetFromMap(
                new IdentityHashMap<>()
        );
        Class<?> type = primaryBaritone.getClass();
        while (type != null && baritoneApiType.isAssignableFrom(type)) {
            collectStaticExecutors(type, executors);
            type = type.getSuperclass();
        }
        for (ThreadPoolExecutor executor : executors) {
            executor.shutdownNow();
        }
        awaitExecutors(executors, timeout);
    }

    private static void collectStaticExecutors(
            Class<?> owner,
            Set<ThreadPoolExecutor> executors
    ) {
        for (Field field : owner.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    || !ThreadPoolExecutor.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                if (field.trySetAccessible()) {
                    Object value = field.get(null);
                    if (value instanceof ThreadPoolExecutor executor) {
                        executors.add(executor);
                    }
                }
            } catch (IllegalAccessException | RuntimeException exception) {
                // Another matching field may still be available for cleanup.
            }
        }
    }

    private static void awaitExecutors(
            Set<ThreadPoolExecutor> executors,
            Duration timeout
    ) {
        long deadline = System.nanoTime() + timeout.toNanos();
        for (ThreadPoolExecutor executor : executors) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            try {
                executor.awaitTermination(remaining, TimeUnit.NANOSECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
