package dev.stowling.client.interaction;

import dev.stowling.automation.AutomationSnapshot;
import dev.stowling.automation.PickupAutomationEngine;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * The only supported entry point for ticking Minecraft-backed automation.
 */
public final class ClientThreadAutomationController {
    private final PickupAutomationEngine engine;
    private final ClientActionScheduler scheduler;

    public ClientThreadAutomationController(
            PickupAutomationEngine engine,
            ClientActionScheduler scheduler
    ) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    public CompletableFuture<AutomationSnapshot> startAsync() {
        return scheduler.submit(() -> {
            engine.start();
            return engine.snapshot();
        });
    }

    public CompletableFuture<AutomationSnapshot> tickAsync() {
        return scheduler.submit(engine::tick);
    }

    public CompletableFuture<AutomationSnapshot> resumeAsync() {
        return scheduler.submit(() -> {
            engine.resume();
            return engine.snapshot();
        });
    }
}
