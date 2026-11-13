package dev.stowling.client.interaction;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public interface ClientActionScheduler {
    boolean isClientThread();

    void execute(Runnable action);

    <T> CompletableFuture<T> submit(Supplier<T> action);
}
