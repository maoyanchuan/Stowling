package dev.stowling.headless;

import dev.stowling.control.StowlingControlService;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class HeadlessControlBridge {
    private static final AtomicReference<StowlingControlService> SERVICE = new AtomicReference<>();

    private HeadlessControlBridge() {
    }

    public static void install(StowlingControlService service) {
        Objects.requireNonNull(service, "service");
        if (!SERVICE.compareAndSet(null, service)) {
            throw new IllegalStateException("A control service is already installed");
        }
    }

    public static void clear(StowlingControlService service) {
        SERVICE.compareAndSet(Objects.requireNonNull(service, "service"), null);
    }

    static StowlingControlService requireService() {
        StowlingControlService service = SERVICE.get();
        if (service == null) {
            throw new IllegalStateException("The Stowling control service is not available");
        }
        return service;
    }
}
