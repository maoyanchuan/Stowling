package dev.stowling.v2.runtime;

public enum TaskRunState {
    QUEUED,
    RUNNING,
    WAITING,
    INTERRUPTING,
    INTERRUPTION_FAILED,
    SUCCEEDED,
    FAILED,
    PREEMPTED,
    CANCELLED
}
