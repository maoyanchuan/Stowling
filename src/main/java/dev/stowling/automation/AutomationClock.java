package dev.stowling.automation;

import java.time.Instant;

public interface AutomationClock {
    Instant now();
}
