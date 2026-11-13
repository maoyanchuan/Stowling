package dev.stowling.automation;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

public final class SystemAutomationClock implements AutomationClock {
    private final Clock clock;

    public SystemAutomationClock() {
        this(Clock.systemUTC());
    }

    public SystemAutomationClock(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Instant now() {
        return clock.instant();
    }
}
