package dev.stowling.task;

import java.util.UUID;

public interface StowlingTask {
    UUID id();

    TaskKind kind();

    TaskStatus status();

    AutomationState state();

    TaskSnapshot snapshot();
}
