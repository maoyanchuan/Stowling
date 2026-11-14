package dev.stowling.schedule;

import java.util.ArrayDeque;
import java.util.Deque;

final class FakeRuntime implements ScheduledTaskRuntime {
    private final Deque<RunOutcome> outcomes = new ArrayDeque<>();
    private StopOutcome stopOutcome = StopOutcome.CLEAN;
    private int tickCount;
    private int requestStopCount;

    void addOutcome(RunOutcome outcome) {
        outcomes.addLast(outcome);
    }

    void succeed(String message) {
        addOutcome(new RunOutcome(RunOutcome.SUCCEEDED, message));
    }

    void fail(String message) {
        addOutcome(new RunOutcome(RunOutcome.FAILED, message));
    }

    void unsafe(String message) {
        addOutcome(new RunOutcome(RunOutcome.UNSAFE, message));
    }

    void setStopOutcome(StopOutcome value) {
        stopOutcome = value;
    }

    int tickCount() {
        return tickCount;
    }

    int requestStopCount() {
        return requestStopCount;
    }

    @Override
    public RunOutcome tick() {
        tickCount++;
        RunOutcome outcome = outcomes.pollFirst();
        return outcome == null ? new RunOutcome(RunOutcome.RUNNING, "running") : outcome;
    }

    @Override
    public void requestStop() {
        requestStopCount++;
    }

    @Override
    public StopOutcome pollStop() {
        return stopOutcome;
    }
}
