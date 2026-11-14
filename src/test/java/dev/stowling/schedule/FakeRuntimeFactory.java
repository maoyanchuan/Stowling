package dev.stowling.schedule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

final class FakeRuntimeFactory implements TaskRuntimeFactory {
    private final Deque<FakeRuntime> runtimes = new ArrayDeque<>();
    private final List<StartCall> startCalls = new ArrayList<>();
    private final List<RunCall> queuedCalls = new ArrayList<>();
    private final List<FinishedCall> finishedCalls = new ArrayList<>();

    void addRuntime(FakeRuntime runtime) {
        runtimes.addLast(runtime);
    }

    List<StartCall> startCalls() {
        return List.copyOf(startCalls);
    }

    List<RunCall> queuedCalls() {
        return List.copyOf(queuedCalls);
    }

    List<FinishedCall> finishedCalls() {
        return List.copyOf(finishedCalls);
    }

    @Override
    public ScheduledTaskRuntime start(UUID runId, TaskDefinition definition) {
        startCalls.add(new StartCall(runId, definition));
        FakeRuntime runtime = runtimes.pollFirst();
        if (runtime == null) {
            throw new IllegalStateException("no fake runtime was prepared");
        }
        return runtime;
    }

    @Override
    public void queued(UUID runId, TaskDefinition definition) {
        queuedCalls.add(new RunCall(runId, definition));
    }

    @Override
    public void finished(
            UUID runId,
            TaskDefinition definition,
            String status,
            String message
    ) {
        finishedCalls.add(new FinishedCall(runId, definition, status, message));
    }

    record StartCall(UUID runId, TaskDefinition definition) {
    }

    record RunCall(UUID runId, TaskDefinition definition) {
    }

    record FinishedCall(
            UUID runId,
            TaskDefinition definition,
            String status,
            String message
    ) {
    }
}
