package dev.stowling.schedule;

public interface ScheduledTaskRuntime {
    RunOutcome tick();

    void requestStop();

    StopOutcome pollStop();
}
