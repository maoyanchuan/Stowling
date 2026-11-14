package dev.stowling.schedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

public final class TaskScheduler {
    private static final String CANCELLED = "CANCELLED";
    private static final String DISABLED = "DISABLED";
    private static final String INTERRUPTED = "INTERRUPTED";
    private static final String INTERRUPTING = "INTERRUPTING";
    private static final String QUEUED = "QUEUED";
    private static final String RUNNING = "RUNNING";
    private static final String STOPPING = "STOPPING";
    private static final String UNSAFE = "UNSAFE";
    private static final String WAITING = "WAITING";
    private static final long ITEM_PROBE_INTERVAL_SECONDS = 1L;
    private static final Comparator<KeyState> QUEUE_COMPARATOR =
            Comparator.comparingInt((KeyState state) -> priorityRank(state.priority))
                    .reversed()
                    .thenComparingLong(state -> state.queueOrder)
                    .thenComparing(state -> String.valueOf(state.key.kind()))
                    .thenComparing(state -> state.key.id());

    private final Supplier<List<TaskDefinition>> definitionsSupplier;
    private final TaskRuntimeFactory factory;
    private final RegionItemProbe probe;
    private final TaskScheduleStore store;
    private final Clock clock;
    private final Map<TaskKey, KeyState> states = new LinkedHashMap<>();
    private final Map<TaskKey, TaskScheduleRecord> lastPersisted = new LinkedHashMap<>();
    private ActiveRun active;
    private boolean unsafeLocked;
    private long nextQueueOrder;

    public TaskScheduler(
            Supplier<List<TaskDefinition>> definitions,
            TaskRuntimeFactory factory,
            RegionItemProbe probe,
            TaskScheduleStore store,
            Clock clock
    ) {
        this.definitionsSupplier = Objects.requireNonNull(definitions, "definitions");
        this.factory = Objects.requireNonNull(factory, "factory");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public synchronized void enable(TaskKey key) {
        Objects.requireNonNull(key, "key");
        TaskDefinition definition = requireDefinition(key);
        KeyState state = stateFor(definition);
        if (state.enabled) {
            return;
        }
        state.enabled = true;
        state.rerunAllowed = true;
        state.message = "enabled";
        Instant now = clock.instant();
        TaskTrigger trigger = definition.settings().trigger();
        switch (trigger.kind()) {
            case AFTER_COMPLETION -> enqueue(
                    state,
                    definition,
                    UUID.randomUUID(),
                    "queued on enable"
            );
            case FIXED_INTERVAL -> state.nextRunAt = nextInterval(trigger, now);
            case DAILY -> state.nextRunAt = nextDaily(trigger, now);
            case ITEMS_PRESENT -> {
                state.nextRunAt = null;
                state.lastProbeAt = null;
            }
            case MANUAL -> state.nextRunAt = null;
            default -> state.nextRunAt = null;
        }
        persistAll();
    }

    public synchronized void disable(TaskKey key) {
        Objects.requireNonNull(key, "key");
        refreshDefinitions();
        KeyState state = states.get(key);
        if (state == null) {
            return;
        }
        state.enabled = false;
        state.nextRunAt = null;
        state.rerunAllowed = false;
        state.message = "disabled";
        clearQueued(state, "disabled before start");
        if (active != null && active.ownerKey.equals(key)) {
            active.stopReason = StopReason.DISABLE;
            active.message = "disable requested";
        }
        persistAll();
    }

    public synchronized UUID runNow(TaskKey key) {
        Objects.requireNonNull(key, "key");
        return requestRun(requireDefinition(key), false);
    }

    public synchronized UUID runNow(TaskDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        refreshDefinitions();
        return requestRun(definition, true);
    }

    public synchronized void tick() {
        Instant now = clock.instant();
        refreshDefinitions();
        if (unsafeLocked) {
            persistAll();
            return;
        }
        evaluateTriggers(now);
        if (active == null) {
            dispatchReady();
            persistAll();
            return;
        }
        if (active.phase == Phase.STOPPING) {
            pollStopping();
            persistAll();
            return;
        }
        if (active.stopReason != null) {
            beginStop(active.stopReason);
            persistAll();
            return;
        }
        if (!active.interruption && hasHigherPriorityChallenger()) {
            beginPreemption();
            persistAll();
            return;
        }
        RunOutcome outcome = tickRuntime(active);
        if (outcome == null) {
            markUnsafe(active, "runtime returned no outcome");
            persistAll();
            return;
        }
        if (RunOutcome.UNSAFE.equals(outcome.state())) {
            markUnsafe(active, outcome.message());
            persistAll();
            return;
        }
        if (outcome.isFinal()) {
            finishActive(outcome.state(), outcome.message(), now);
            dispatchReady();
            persistAll();
            return;
        }
        KeyState owner = states.get(active.ownerKey);
        if (owner != null) {
            owner.message = outcome.message();
        }
        persistAll();
    }

    public synchronized void cancelRun(UUID runId) {
        Objects.requireNonNull(runId, "runId");
        if (active != null && active.runId.equals(runId)) {
            active.stopReason = StopReason.CANCEL;
            active.message = "cancel requested";
            persistAll();
            return;
        }
        for (KeyState state : states.values()) {
            if (runId.equals(state.queuedRunId)) {
                cancelQueued(state, "run cancelled before start");
                persistAll();
                return;
            }
        }
        persistAll();
    }

    public synchronized List<TaskScheduleInfo> snapshots() {
        List<KeyState> ordered = new ArrayList<>(states.values());
        ordered.sort(Comparator
                .comparing((KeyState state) -> String.valueOf(state.key.kind()))
                .thenComparing(state -> state.key.id()));
        List<TaskScheduleInfo> snapshots = new ArrayList<>(ordered.size());
        for (KeyState state : ordered) {
            snapshots.add(snapshot(state));
        }
        return List.copyOf(snapshots);
    }

    public synchronized boolean isBusy() {
        return active != null || unsafeLocked;
    }

    public synchronized void restore() {
        if (active != null) {
            throw new IllegalStateException("cannot restore while a run is active");
        }
        states.clear();
        lastPersisted.clear();
        unsafeLocked = false;
        nextQueueOrder = 0L;
        refreshDefinitions();
        List<TaskScheduleRecord> records = store.load();
        List<KeyState> rerunStates = new ArrayList<>();
        List<RestoredQueue> restoredQueues = new ArrayList<>();
        long restoredQueueOrder = -1L;
        for (TaskScheduleRecord record : records) {
            TaskDefinition definition = definitionFor(record.key());
            TaskPriority priority = definition == null
                    ? record.priority()
                    : definition.settings().priority();
            KeyState state = new KeyState(record.key(), definition, priority);
            state.configured = definition != null;
            states.put(record.key(), state);
            state.lastFinishedAt = record.lastFinishedAt();
            state.lastProbeAt = record.lastProbeAt();
            boolean allowed = record.enabled()
                    && definition != null
                    && definition.settings().autoStartOnRestart();
            state.enabled = allowed;
            state.nextRunAt = allowed ? record.nextRunAt() : null;
            state.rerunAllowed = allowed;
            state.message = record.message();
            if (record.unsafeLocked()) {
                unsafeLocked = true;
                state.unsafe = true;
            }
            if (isOldActive(record.state())) {
                cancelOldActive(record);
                if (allowed && !record.unsafeLocked()) {
                    rerunStates.add(state);
                }
            }
            if (record.queued() && record.queuedRunId() != null && definition != null) {
                factory.finished(
                        record.queuedRunId(),
                        definition,
                        CANCELLED,
                        "queued run replaced after restart"
                );
                if (allowed) {
                    UUID replacementRunId = UUID.randomUUID();
                    state.queuedRunId = replacementRunId;
                    state.queueOrder = record.queueOrder();
                    state.message = QUEUED;
                    restoredQueueOrder = Math.max(restoredQueueOrder, record.queueOrder());
                    restoredQueues.add(new RestoredQueue(
                            replacementRunId,
                            definition
                    ));
                }
            }
        }
        nextQueueOrder = Math.max(nextQueueOrder, restoredQueueOrder + 1L);
        for (RestoredQueue restored : restoredQueues) {
            factory.queued(restored.runId(), restored.definition());
        }
        for (KeyState state : rerunStates) {
            if (state.queuedRunId == null && state.definition != null) {
                enqueue(state, state.definition, UUID.randomUUID(), "rerun after restart");
            }
        }
        persistAll();
    }

    private UUID requestRun(TaskDefinition definition, boolean override) {
        KeyState state = stateFor(definition);
        if (state.queuedRunId != null) {
            return state.queuedRunId;
        }
        UUID runId = UUID.randomUUID();
        if (override) {
            enqueueOverride(state, definition, runId, "run requested");
        } else {
            enqueue(state, definition, runId, "run requested");
        }
        persistAll();
        return runId;
    }

    private void evaluateTriggers(Instant now) {
        for (KeyState state : List.copyOf(states.values())) {
            TaskDefinition definition = state.definition;
            if (!state.enabled || definition == null) {
                continue;
            }
            TaskTrigger trigger = definition.settings().trigger();
            if (trigger.kind() == TriggerKind.ITEMS_PRESENT) {
                evaluateItemTrigger(state, trigger, now);
                continue;
            }
            if (state.nextRunAt == null || now.isBefore(state.nextRunAt)) {
                continue;
            }
            switch (trigger.kind()) {
                case FIXED_INTERVAL -> {
                    enqueue(state, definition, UUID.randomUUID(), "fixed interval elapsed");
                    state.nextRunAt = nextIntervalAfter(
                            trigger,
                            state.nextRunAt,
                            now
                    );
                }
                case DAILY -> {
                    enqueue(state, definition, UUID.randomUUID(), "daily time reached");
                    state.nextRunAt = nextDaily(trigger, now);
                }
                case AFTER_COMPLETION -> {
                    state.nextRunAt = null;
                    enqueue(state, definition, UUID.randomUUID(), "after-completion delay elapsed");
                }
                default -> {
                    state.nextRunAt = null;
                }
            }
        }
    }

    private void evaluateItemTrigger(KeyState state, TaskTrigger trigger, Instant now) {
        Instant nextProbe = state.lastProbeAt == null
                ? now
                : state.lastProbeAt.plusSeconds(ITEM_PROBE_INTERVAL_SECONDS);
        if (now.isBefore(nextProbe)) {
            return;
        }
        state.lastProbeAt = now;
        state.nextRunAt = now.plusSeconds(ITEM_PROBE_INTERVAL_SECONDS);
        RegionItemProbe.Observation observation = probe.observe(regions(state.definition, trigger));
        if (observation == RegionItemProbe.Observation.PRESENT) {
            enqueue(state, state.definition, UUID.randomUUID(), "items present");
        } else if (observation == RegionItemProbe.Observation.UNOBSERVABLE) {
            state.message = "items unobservable";
        } else {
            state.message = WAITING;
        }
    }

    private void dispatchReady() {
        if (active != null || unsafeLocked) {
            return;
        }
        KeyState next = highestQueued(null);
        if (next == null || next.queuedRunId == null) {
            return;
        }
        TaskDefinition definition = next.queuedDefinition == null
                ? next.definition
                : next.queuedDefinition;
        if (definition == null) {
            return;
        }
        UUID runId = next.queuedRunId;
        boolean definitionOverride = next.queuedDefinition != null;
        next.queuedRunId = null;
        next.queuedDefinition = null;
        next.queueOrder = 0L;
        next.rerunAllowed = true;
        next.message = "starting";
        ActiveRun run = new ActiveRun(
                runId,
                definition,
                next.key,
                next.priority,
                false,
                definitionOverride
        );
        active = run;
        try {
            run.runtime = Objects.requireNonNull(
                    factory.start(runId, definition),
                    "factory returned no runtime"
            );
        } catch (RuntimeException exception) {
            markUnsafe(run, "runtime failed to start");
        }
    }

    private RunOutcome tickRuntime(ActiveRun run) {
        if (run.runtime == null) {
            return new RunOutcome(RunOutcome.UNSAFE, "runtime is unavailable");
        }
        try {
            return run.runtime.tick();
        } catch (RuntimeException exception) {
            return new RunOutcome(RunOutcome.UNSAFE, "runtime tick failed");
        }
    }

    private void finishActive(String status, String message, Instant now) {
        ActiveRun finished = active;
        if (finished == null) {
            return;
        }
        notifyFinished(finished, status, message);
        KeyState owner = states.get(finished.ownerKey);
        if (owner != null && finished.interruption) {
            owner.message = message;
        } else if (owner != null) {
            owner.rerunAllowed = false;
            owner.message = message;
            if (RunOutcome.SUCCEEDED.equals(status)) {
                owner.lastFinishedAt = now;
                if (owner.enabled && owner.definition != null
                        && owner.definition.settings().trigger().kind()
                        == TriggerKind.AFTER_COMPLETION) {
                    owner.nextRunAt = nextAfterCompletion(owner.definition, now);
                }
            }
        }
        active = null;
    }

    private void beginPreemption() {
        active.stopReason = StopReason.PREEMPTION;
        beginStop(StopReason.PREEMPTION);
    }

    private void beginStop(StopReason reason) {
        if (active == null || active.phase != Phase.RUNNING) {
            return;
        }
        active.stopReason = reason;
        try {
            active.runtime.requestStop();
            active.phase = Phase.STOPPING;
            active.message = switch (reason) {
                case PREEMPTION -> "preemption requested";
                case DISABLE -> "disable requested";
                case CANCEL -> "cancel requested";
            };
        } catch (RuntimeException exception) {
            markUnsafe(active, "runtime stop request failed");
        }
    }

    private void pollStopping() {
        StopOutcome outcome;
        try {
            outcome = active.runtime.pollStop();
        } catch (RuntimeException exception) {
            outcome = StopOutcome.UNSAFE;
        }
        if (outcome == null || outcome == StopOutcome.UNSAFE) {
            markUnsafe(active, "runtime stop was unsafe");
            return;
        }
        if (outcome == StopOutcome.PENDING) {
            active.message = "waiting for clean stop";
            return;
        }
        ActiveRun stopped = active;
        active = null;
        boolean preempted = stopped.stopReason == StopReason.PREEMPTION;
        String status = preempted ? INTERRUPTED : CANCELLED;
        notifyFinished(stopped, status, "run stopped cleanly");
        KeyState owner = states.get(stopped.ownerKey);
        if (owner != null) {
            owner.message = preempted ? "preempted" : "run cancelled";
            if (preempted && owner.rerunAllowed && owner.queuedRunId == null) {
                if (stopped.definitionOverride) {
                    enqueueOverride(
                            owner,
                            stopped.definition,
                            UUID.randomUUID(),
                            "rerun after preemption"
                    );
                } else if (owner.definition != null) {
                    enqueue(
                            owner,
                            owner.definition,
                            UUID.randomUUID(),
                            "rerun after preemption"
                    );
                }
            }
        }
        if (stopped.interruption) {
            dispatchReady();
            return;
        }
        TaskDefinition interruption = interruptionDefinition(stopped.definition);
        if (interruption == null) {
            dispatchReady();
            return;
        }
        startInterruption(stopped.ownerKey, interruption);
    }

    private void startInterruption(TaskKey ownerKey, TaskDefinition definition) {
        UUID runId = UUID.randomUUID();
        factory.queued(runId, definition);
        KeyState owner = states.get(ownerKey);
        TaskPriority priority = owner == null
                ? definition.settings().priority()
                : owner.priority;
        ActiveRun run = new ActiveRun(
                runId,
                definition,
                ownerKey,
                priority,
                true,
                true
        );
        run.message = "interruption running";
        active = run;
        if (owner != null) {
            owner.message = INTERRUPTING;
        }
        try {
            run.runtime = Objects.requireNonNull(
                    factory.start(runId, definition),
                    "factory returned no interruption runtime"
            );
        } catch (RuntimeException exception) {
            markUnsafe(run, "interruption runtime failed to start");
        }
    }

    private void markUnsafe(ActiveRun run, String message) {
        unsafeLocked = true;
        run.phase = Phase.UNSAFE;
        run.message = message;
        notifyFinished(run, RunOutcome.UNSAFE, message);
        KeyState owner = states.get(run.ownerKey);
        if (owner != null) {
            owner.unsafe = true;
            owner.message = message;
        }
    }

    private void notifyFinished(ActiveRun run, String status, String message) {
        if (run.terminalNotified) {
            return;
        }
        run.terminalNotified = true;
        factory.finished(run.runId, run.definition, status, message);
    }

    private TaskDefinition interruptionDefinition(TaskDefinition source) {
        TaskInterruption interruption = source.settings().interruption();
        if (interruption == null || interruption.kind() == InterruptionKind.NONE) {
            return null;
        }
        if (interruption.kind() == InterruptionKind.INLINE) {
            return new TaskDefinition(
                    TaskKey.AUTOMATION,
                    source.id(),
                    source.name(),
                    TaskSettings.defaults(),
                    null,
                    interruption.steps()
            );
        }
        if (interruption.kind() == InterruptionKind.TASK_REFERENCE) {
            if (interruption.taskId() == null) {
                return null;
            }
            TaskDefinition target = definitionFor(new TaskKey(
                    TaskKey.AUTOMATION,
                    interruption.taskId()
            ));
            if (target == null) {
                return null;
            }
            return new TaskDefinition(
                    TaskKey.AUTOMATION,
                    target.id(),
                    target.name(),
                    TaskSettings.defaults(),
                    null,
                    target.steps()
            );
        }
        return null;
    }

    private KeyState highestQueued(TaskKey excludedKey) {
        return states.values().stream()
                .filter(state -> state.queuedRunId != null)
                .filter(state -> excludedKey == null || !excludedKey.equals(state.key))
                .filter(state -> state.queuedDefinition != null
                        || state.definition != null)
                .min(QUEUE_COMPARATOR)
                .orElse(null);
    }

    private boolean hasHigherPriorityChallenger() {
        KeyState challenger = highestQueued(active.ownerKey);
        return challenger != null
                && priorityRank(challenger.priority) > priorityRank(active.priority);
    }

    private void enqueue(
            KeyState state,
            TaskDefinition definition,
            UUID runId,
            String message
    ) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(runId, "runId");
        if (state.queuedRunId != null) {
            return;
        }
        state.queuedRunId = runId;
        state.queuedDefinition = null;
        state.queueOrder = nextQueueOrder++;
        state.message = message;
        factory.queued(runId, definition);
    }

    private void enqueueOverride(
            KeyState state,
            TaskDefinition definition,
            UUID runId,
            String message
    ) {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(runId, "runId");
        if (state.queuedRunId != null) {
            return;
        }
        state.queuedRunId = runId;
        state.queuedDefinition = definition;
        if (state.definition == null) {
            state.priority = definition.settings().priority();
        }
        state.queueOrder = nextQueueOrder++;
        state.message = message;
        factory.queued(runId, definition);
    }

    private void cancelQueued(KeyState state, String message) {
        UUID runId = state.queuedRunId;
        if (runId == null) {
            return;
        }
        TaskDefinition definition = state.queuedDefinition == null
                ? state.definition
                : state.queuedDefinition;
        state.queuedRunId = null;
        state.queuedDefinition = null;
        state.queueOrder = 0L;
        state.message = message;
        if (definition != null) {
            factory.finished(runId, definition, CANCELLED, message);
        }
    }

    private void clearQueued(KeyState state, String message) {
        cancelQueued(state, message);
    }

    private void cancelOldActive(TaskScheduleRecord record) {
        if (record.runId() == null) {
            return;
        }
        TaskDefinition definition = definitionFor(record.runTaskKey());
        if (definition == null) {
            definition = definitionFor(record.key());
        }
        if (definition != null) {
            factory.finished(
                    record.runId(),
                    definition,
                    CANCELLED,
                    "run interrupted by restart"
            );
        }
    }

    private TaskScheduleInfo snapshot(KeyState state) {
        String stateName = stateName(state);
        UUID runId = state.queuedRunId;
        String message = state.message;
        if (active != null && active.ownerKey.equals(state.key)) {
            runId = active.runId;
            message = active.message;
        }
        return new TaskScheduleInfo(
                state.key,
                state.enabled,
                stateName,
                state.priority,
                state.nextRunAt,
                runId,
                message,
                state.lastFinishedAt
        );
    }

    private String stateName(KeyState state) {
        if (active != null && active.ownerKey.equals(state.key)) {
            if (active.phase == Phase.UNSAFE) {
                return UNSAFE;
            }
            if (active.interruption) {
                return INTERRUPTING;
            }
            if (active.phase == Phase.STOPPING) {
                return STOPPING;
            }
            if (active.stopReason != null) {
                return STOPPING;
            }
            return RUNNING;
        }
        if (state.unsafe) {
            return UNSAFE;
        }
        if (state.queuedRunId != null) {
            return QUEUED;
        }
        return state.enabled ? WAITING : DISABLED;
    }

    private void persistAll() {
        for (KeyState state : states.values()) {
            TaskScheduleRecord record = toRecord(state);
            if (record.equals(lastPersisted.get(state.key))) {
                continue;
            }
            store.save(record);
            lastPersisted.put(state.key, record);
        }
    }

    private TaskScheduleRecord toRecord(KeyState state) {
        boolean isActiveOwner = active != null && active.ownerKey.equals(state.key);
        UUID runId = isActiveOwner ? active.runId : state.queuedRunId;
        TaskKey runTaskKey = isActiveOwner ? active.definition.key() : null;
        return new TaskScheduleRecord(
                state.key,
                state.enabled,
                stateName(state),
                state.priority,
                state.nextRunAt,
                state.lastFinishedAt,
                runId,
                runTaskKey,
                state.queuedRunId != null,
                state.queuedRunId,
                state.queueOrder,
                isActiveOwner && !active.interruption && state.rerunAllowed,
                state.message,
                state.unsafe,
                state.lastProbeAt
        );
    }

    private void refreshDefinitions() {
        List<TaskDefinition> definitions = definitionsSupplier.get();
        if (definitions == null) {
            return;
        }
        Map<TaskKey, TaskDefinition> currentDefinitions = new LinkedHashMap<>();
        for (TaskDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            currentDefinitions.put(definition.key(), definition);
        }
        Instant now = clock.instant();
        for (TaskDefinition definition : currentDefinitions.values()) {
            TaskKey key = definition.key();
            KeyState state = states.computeIfAbsent(key, ignored -> new KeyState(
                    key,
                    null,
                    definition.settings().priority()
            ));
            TaskDefinition previous = state.definition;
            boolean wasConfigured = state.configured;
            state.definition = definition;
            state.priority = definition.settings().priority();
            state.configured = true;
            if (wasConfigured
                    && state.enabled
                    && previous != null
                    && !previous.settings().trigger().equals(definition.settings().trigger())) {
                rearmTrigger(state, definition.settings().trigger(), now);
            }
        }
        for (KeyState state : List.copyOf(states.values())) {
            if (state.configured && !currentDefinitions.containsKey(state.key)) {
                removeDefinition(state);
            }
        }
    }

    private void rearmTrigger(KeyState state, TaskTrigger trigger, Instant now) {
        switch (trigger.kind()) {
            case FIXED_INTERVAL -> state.nextRunAt = nextInterval(trigger, now);
            case DAILY -> state.nextRunAt = nextDaily(trigger, now);
            case AFTER_COMPLETION -> {
                state.nextRunAt = null;
                boolean activeNormal = active != null
                        && active.ownerKey.equals(state.key)
                        && !active.interruption;
                if (!activeNormal && state.definition != null) {
                    enqueue(
                            state,
                            state.definition,
                            UUID.randomUUID(),
                            "queued after trigger change"
                    );
                }
            }
            case ITEMS_PRESENT -> {
                state.nextRunAt = null;
                state.lastProbeAt = null;
            }
            case MANUAL -> state.nextRunAt = null;
        }
    }

    private void removeDefinition(KeyState state) {
        state.enabled = false;
        state.nextRunAt = null;
        state.rerunAllowed = false;
        state.lastProbeAt = null;
        state.configured = false;
        state.message = "definition removed";
        clearQueued(state, "definition removed before start");
        state.definition = null;
        if (active != null && active.ownerKey.equals(state.key)
                && active.phase != Phase.UNSAFE) {
            active.stopReason = StopReason.DISABLE;
            active.message = "definition removed";
        }
    }

    private TaskDefinition requireDefinition(TaskKey key) {
        refreshDefinitions();
        TaskDefinition definition = definitionFor(key);
        if (definition == null) {
            throw new IllegalArgumentException("unknown task definition: " + key);
        }
        return definition;
    }

    private TaskDefinition definitionFor(TaskKey key) {
        if (key == null) {
            return null;
        }
        KeyState state = states.get(key);
        return state == null ? null : state.definition;
    }

    private KeyState stateFor(TaskDefinition definition) {
        TaskKey key = Objects.requireNonNull(definition.key(), "definition.key");
        return states.computeIfAbsent(key, ignored -> new KeyState(
                key,
                null,
                definition.settings().priority()
        ));
    }

    private static boolean isOldActive(String state) {
        return RUNNING.equals(state)
                || STOPPING.equals(state)
                || INTERRUPTING.equals(state)
                || UNSAFE.equals(state);
    }

    private static int priorityRank(TaskPriority priority) {
        return switch (priority) {
            case LOW -> 0;
            case MEDIUM -> 1;
            case HIGH -> 2;
        };
    }

    private static Instant nextInterval(TaskTrigger trigger, Instant now) {
        long seconds = intervalSeconds(trigger);
        return seconds > 0L ? now.plusSeconds(seconds) : null;
    }

    private static Instant nextIntervalAfter(
            TaskTrigger trigger,
            Instant dueAt,
            Instant now
    ) {
        long seconds = intervalSeconds(trigger);
        if (seconds <= 0L) {
            return null;
        }
        long elapsedSeconds = Duration.between(dueAt, now).getSeconds();
        long periods = Math.max(1L, elapsedSeconds / seconds + 1L);
        return dueAt.plusSeconds(Math.multiplyExact(periods, seconds));
    }

    private static Instant nextAfterCompletion(TaskDefinition definition, Instant now) {
        long seconds = intervalSeconds(definition.settings().trigger());
        return now.plusSeconds(Math.max(0L, seconds));
    }

    private static long intervalSeconds(TaskTrigger trigger) {
        return trigger.intervalSeconds();
    }

    private static Instant nextDaily(TaskTrigger trigger, Instant now) {
        ZoneId zone = zoneId(trigger);
        List<LocalTime> times = dailyTimes(trigger);
        if (zone == null || times.isEmpty()) {
            return null;
        }
        ZonedDateTime zonedNow = ZonedDateTime.ofInstant(now, zone);
        for (LocalTime time : times) {
            ZonedDateTime candidate = zonedNow.toLocalDate().atTime(time).atZone(zone);
            if (candidate.toInstant().isAfter(now)) {
                return candidate.toInstant();
            }
        }
        LocalTime first = times.getFirst();
        return zonedNow.toLocalDate().plusDays(1).atTime(first).atZone(zone).toInstant();
    }

    private static ZoneId zoneId(TaskTrigger trigger) {
        try {
            return ZoneId.of(trigger.zoneId());
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static List<LocalTime> dailyTimes(TaskTrigger trigger) {
        List<LocalTime> times = new ArrayList<>();
        for (String rawTime : trigger.times()) {
            try {
                times.add(LocalTime.parse(rawTime));
            } catch (RuntimeException exception) {
                continue;
            }
        }
        times.sort(Comparator.naturalOrder());
        return List.copyOf(times);
    }

    private static List<String> regions(TaskDefinition definition, TaskTrigger trigger) {
        List<String> configured = trigger.regionIds().stream()
                .filter(regionId -> !regionId.isBlank())
                .toList();
        if (!configured.isEmpty()) {
            return configured;
        }
        if (definition.pickup() == null) {
            return List.of();
        }
        return definition.pickup().regions().stream()
                .map(binding -> binding.regionId())
                .toList();
    }

    private enum Phase {
        RUNNING,
        STOPPING,
        UNSAFE
    }

    private static final class ActiveRun {
        private final UUID runId;
        private final TaskDefinition definition;
        private final TaskKey ownerKey;
        private final TaskPriority priority;
        private final boolean interruption;
        private final boolean definitionOverride;
        private ScheduledTaskRuntime runtime;
        private Phase phase = Phase.RUNNING;
        private String message = "running";
        private StopReason stopReason;
        private boolean terminalNotified;

        private ActiveRun(
                UUID runId,
                TaskDefinition definition,
                TaskKey ownerKey,
                TaskPriority priority,
                boolean interruption,
                boolean definitionOverride
        ) {
            this.runId = runId;
            this.definition = definition;
            this.ownerKey = ownerKey;
            this.priority = priority;
            this.interruption = interruption;
            this.definitionOverride = definitionOverride;
        }
    }

    private enum StopReason {
        PREEMPTION,
        DISABLE,
        CANCEL
    }

    private record RestoredQueue(
            UUID runId,
            TaskDefinition definition
    ) {
    }

    private static final class KeyState {
        private final TaskKey key;
        private TaskDefinition definition;
        private TaskPriority priority;
        private boolean enabled;
        private Instant nextRunAt;
        private Instant lastFinishedAt;
        private UUID queuedRunId;
        private TaskDefinition queuedDefinition;
        private long queueOrder;
        private boolean rerunAllowed;
        private boolean configured;
        private boolean unsafe;
        private String message = "";
        private Instant lastProbeAt;

        private KeyState(TaskKey key, TaskDefinition definition, TaskPriority priority) {
            this.key = key;
            this.definition = definition;
            this.priority = priority;
        }
    }
}
