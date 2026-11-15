package dev.stowling.v2.runtime;

public enum TaskRunState {
    QUEUED,
    RUNNING,
    WAITING,
    SUCCEEDED,
    FAILED,
    PREEMPTED,
    CANCELLED
}
