package dev.stowling.client.automation;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.AutomationPauseReason;
import dev.stowling.automation.AutomationSnapshot;
import dev.stowling.automation.PickupAutomationEngine;
import dev.stowling.automation.PickupAutomationPlan;
import dev.stowling.automation.PickupWorkflowPort;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.NamedCache;
import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupWorkflow;
import dev.stowling.schedule.RegionItemProbe;
import dev.stowling.schedule.StopOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Uses process-local pickup state so generic task checkpoints remain untouched.
 */
public final class ClientPickupWorkflowPort implements PickupWorkflowPort {
    private static final int INVENTORY_RESERVE_SLOTS = 2;
    private static final int MAX_CONTAINER_RETRIES = 3;
    private static final Duration CONTAINER_ACK_TIMEOUT = Duration.ofSeconds(8);
    private static final Duration NAVIGATION_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration REGION_PROBE_INTERVAL = Duration.ofSeconds(1);
    private static final String MANUAL_RECOVERY =
            "拾取流程的进程内操作已丢失；请人工确认拾取状态后重新创建任务。";

    private final Supplier<StowlingConfig> config;
    private final PickupEngineFactory engineFactory;
    private final ClientActionScheduler scheduler;
    private final RegionItemProbe probe;
    private final Clock clock;
    private final Map<String, WorkflowEntry> attempts = new LinkedHashMap<>();
    private final Map<UUID, StopOutcome> stopOutcomes = new LinkedHashMap<>();

    public ClientPickupWorkflowPort(
            Supplier<StowlingConfig> config,
            PickupEngineFactory engineFactory,
            ClientActionScheduler scheduler
    ) {
        this(
                config,
                engineFactory,
                scheduler,
                ignored -> RegionItemProbe.Observation.UNOBSERVABLE,
                Clock.systemUTC()
        );
    }

    public ClientPickupWorkflowPort(
            Supplier<StowlingConfig> config,
            PickupEngineFactory engineFactory,
            ClientActionScheduler scheduler,
            RegionItemProbe probe,
            Clock clock
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public synchronized AutomationOperationResult start(
            String operationKey,
            Request request
    ) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(request, "request");
        if (attempts.containsKey(operationKey)) {
            return poll(operationKey);
        }
        if (!scheduler.isClientThread()) {
            return AutomationOperationResult.paused(
                    "拾取流程只能在 Minecraft 客户端线程启动。"
            );
        }
        UUID taskId = taskId(operationKey);
        if (taskId == null) {
            return AutomationOperationResult.failed("拾取操作键无效。");
        }
        StowlingConfig loaded = Objects.requireNonNull(config.get(), "config");
        try {
            PickupAutomationPlan pickupPlan;
            boolean customRegions = request.customRules().isPresent();
            if (customRegions) {
                pickupPlan = request.customRules().orElseThrow().plan(taskId, loaded);
            } else {
                Optional<PickupWorkflow> workflow = loaded.pickupWorkflows().stream()
                        .filter(candidate -> candidate.id().equals(request.workflowId()))
                        .findFirst();
                if (workflow.isEmpty()) {
                    return AutomationOperationResult.failed(
                            "未知拾取流程: " + request.workflowId()
                    );
                }
                pickupPlan = plan(taskId, workflow.orElseThrow(), request.regionId(), loaded);
            }
            if (request.waitForItems()) {
                WaitingAttempt waiting = new WaitingAttempt(pickupPlan, customRegions);
                attempts.put(operationKey, waiting);
                stopOutcomes.remove(taskId);
                return advanceWaiting(operationKey, waiting);
            }
            PickupAutomationEngine engine = engineFactory.create(pickupPlan);
            engine.start();
            stopOutcomes.remove(taskId);
            attempts.put(operationKey, new PickupAttempt(engine));
            return map(operationKey, engine.tick());
        } catch (RuntimeException exception) {
            attempts.remove(operationKey);
            return AutomationOperationResult.failed(
                    "拾取流程启动失败: " + exception.getMessage()
            );
        }
    }

    @Override
    public synchronized AutomationOperationResult poll(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        WorkflowEntry entry = attempts.get(operationKey);
        if (entry == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        if (!scheduler.isClientThread()) {
            return AutomationOperationResult.paused(
                    "拾取流程只能在 Minecraft 客户端线程轮询。"
            );
        }
        if (entry instanceof WaitingAttempt waiting) {
            return advanceWaiting(operationKey, waiting);
        }
        PickupAttempt attempt = (PickupAttempt) entry;
        return map(operationKey, attempt.engine().tick());
    }

    @Override
    public synchronized AutomationOperationResult reconcile(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        WorkflowEntry entry = attempts.get(operationKey);
        if (entry == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        if (!scheduler.isClientThread()) {
            return AutomationOperationResult.paused(
                    "拾取流程只能在 Minecraft 客户端线程恢复。"
            );
        }
        if (entry instanceof WaitingAttempt waiting) {
            return advanceWaiting(operationKey, waiting);
        }
        PickupAttempt attempt = (PickupAttempt) entry;
        return map(operationKey, attempt.engine().tick());
    }

    @Override
    public synchronized StopOutcome requestStop(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (!scheduler.isClientThread()) {
            return StopOutcome.UNSAFE;
        }
        List<Map.Entry<String, WorkflowEntry>> matches = attemptEntries(attemptId);
        if (matches.isEmpty()) {
            return stopOutcomes.getOrDefault(attemptId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = StopOutcome.CLEAN;
        for (Map.Entry<String, WorkflowEntry> entry : matches) {
            StopOutcome current = entry.getValue().requestStopAndPoll();
            outcome = combine(outcome, current);
            if (current == StopOutcome.CLEAN || current == StopOutcome.UNSAFE) {
                attempts.remove(entry.getKey());
            }
        }
        return retainStopOutcome(attemptId, outcome);
    }

    @Override
    public synchronized StopOutcome pollStop(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (!scheduler.isClientThread()) {
            return StopOutcome.UNSAFE;
        }
        List<Map.Entry<String, WorkflowEntry>> matches = attemptEntries(attemptId);
        if (matches.isEmpty()) {
            return stopOutcomes.getOrDefault(attemptId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = StopOutcome.CLEAN;
        for (Map.Entry<String, WorkflowEntry> entry : matches) {
            StopOutcome current = entry.getValue().pollStop();
            outcome = combine(outcome, current);
            if (current == StopOutcome.CLEAN || current == StopOutcome.UNSAFE) {
                attempts.remove(entry.getKey());
            }
        }
        return retainStopOutcome(attemptId, outcome);
    }

    private AutomationOperationResult map(
            String operationKey,
            AutomationSnapshot snapshot
    ) {
        return switch (snapshot.stage()) {
            case COMPLETE -> complete(operationKey, snapshot);
            case FAILED -> {
                yield AutomationOperationResult.failed(pauseDetail(snapshot));
            }
            case PAUSED -> AutomationOperationResult.paused(
                    pauseDetail(snapshot)
            );
            default -> AutomationOperationResult.running(
                    snapshot.stage().name()
            );
        };
    }

    private AutomationOperationResult complete(
            String operationKey,
            AutomationSnapshot snapshot
    ) {
        WorkflowEntry attempt = attempts.get(operationKey);
        if (attempt == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        StopOutcome outcome = attempt.requestStopAndPoll();
        UUID taskId = taskId(operationKey);
        if (outcome == StopOutcome.CLEAN) {
            attempts.remove(operationKey);
            if (taskId != null) {
                retainStopOutcome(taskId, outcome);
            }
        } else if (outcome == StopOutcome.UNSAFE && taskId != null) {
            retainStopOutcome(taskId, outcome);
        }
        return stopCompletionResult(snapshot.workflowId(), outcome);
    }

    static AutomationOperationResult stopCompletionResult(
            String workflowId,
            StopOutcome outcome
    ) {
        Objects.requireNonNull(workflowId, "workflowId");
        return switch (Objects.requireNonNull(outcome, "outcome")) {
            case CLEAN -> AutomationOperationResult.succeeded(
                    "RUN_PICKUP:" + workflowId
            );
            case PENDING -> AutomationOperationResult.running(
                    "waiting for clean pickup stop"
            );
            case UNSAFE -> AutomationOperationResult.failed(
                    "pickup completed but safe stop could not be confirmed"
            );
        };
    }

    private static String pauseDetail(AutomationSnapshot snapshot) {
        return snapshot.pauseReason()
                .map(AutomationPauseReason::detail)
                .orElse("拾取流程已暂停。");
    }

    private static UUID taskId(String operationKey) {
        int separator = operationKey.indexOf(':');
        String candidate = separator < 0
                ? operationKey
                : operationKey.substring(0, separator);
        try {
            return UUID.fromString(candidate);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private List<Map.Entry<String, WorkflowEntry>> attemptEntries(UUID taskId) {
        List<Map.Entry<String, WorkflowEntry>> result = new ArrayList<>();
        for (Map.Entry<String, WorkflowEntry> entry : attempts.entrySet()) {
            if (taskId.equals(taskId(entry.getKey()))) {
                result.add(Map.entry(entry.getKey(), entry.getValue()));
            }
        }
        return result;
    }

    private static StopOutcome combine(StopOutcome first, StopOutcome second) {
        if (first == StopOutcome.UNSAFE || second == StopOutcome.UNSAFE) {
            return StopOutcome.UNSAFE;
        }
        if (first == StopOutcome.PENDING || second == StopOutcome.PENDING) {
            return StopOutcome.PENDING;
        }
        return StopOutcome.CLEAN;
    }

    private StopOutcome retainStopOutcome(UUID taskId, StopOutcome outcome) {
        if (outcome == StopOutcome.PENDING) {
            return outcome;
        }
        stopOutcomes.put(taskId, outcome);
        if (stopOutcomes.size() > 128) {
            UUID oldest = stopOutcomes.keySet().iterator().next();
            stopOutcomes.remove(oldest);
        }
        return outcome;
    }

    private AutomationOperationResult advanceWaiting(
            String operationKey,
            WaitingAttempt waiting
    ) {
        Instant now = clock.instant();
        Optional<Instant> lastProbeAt = waiting.lastProbeAt();
        if (lastProbeAt.isPresent()
                && now.isBefore(lastProbeAt.orElseThrow().plus(REGION_PROBE_INTERVAL))) {
            return waiting.result();
        }
        List<String> regionIds = waiting.plan().orderedRegions().stream()
                .map(binding -> binding.regionId())
                .toList();
        RegionItemProbe.Observation observation = waiting.customRegions()
                ? probe.observeRegions(regionIds.stream()
                        .map(waiting.plan().regionsById()::get)
                        .toList())
                : probe.observe(regionIds);
        if (observation == RegionItemProbe.Observation.PRESENT) {
            PickupAutomationEngine engine = engineFactory.create(waiting.plan());
            engine.start();
            attempts.put(operationKey, new PickupAttempt(engine));
            return map(operationKey, engine.tick());
        }
        WaitingAttempt updated = new WaitingAttempt(
                waiting.plan(),
                waiting.customRegions(),
                Optional.of(now),
                Optional.of(observation)
        );
        attempts.put(operationKey, updated);
        return waitingResult(observation);
    }

    private static AutomationOperationResult waitingResult(
            RegionItemProbe.Observation observation
    ) {
        return switch (observation) {
            case EMPTY -> AutomationOperationResult.running("等待物品出现");
            case UNOBSERVABLE -> AutomationOperationResult.running("等待区域可见");
            case PRESENT -> throw new IllegalArgumentException(
                    "present observation must start the pickup engine"
            );
        };
    }

    private static PickupAutomationPlan plan(
            UUID taskId,
            PickupWorkflow workflow,
            Optional<String> regionOverride,
            StowlingConfig config
    ) {
        Map<String, PickupRegion> regions = new LinkedHashMap<>();
        for (PickupRegion region : config.pickupRegions()) {
            regions.put(region.id(), region);
        }
        Map<String, ContainerRef> caches = new LinkedHashMap<>();
        for (NamedCache cache : config.cacheContainers()) {
            caches.put(cache.id(), cache.toContainerRef());
        }
        return new PickupAutomationPlan(
                taskId,
                workflow,
                regionOverride,
                regions,
                caches,
                config.storageRoutes(),
                config.overflowContainer(),
                INVENTORY_RESERVE_SLOTS,
                MAX_CONTAINER_RETRIES,
                CONTAINER_ACK_TIMEOUT,
                NAVIGATION_TIMEOUT
        );
    }

    @FunctionalInterface
    public interface PickupEngineFactory {
        PickupAutomationEngine create(PickupAutomationPlan plan);
    }

    private interface WorkflowEntry {
        StopOutcome requestStopAndPoll();

        StopOutcome pollStop();
    }

    private record PickupAttempt(
            PickupAutomationEngine engine
    ) implements WorkflowEntry {
        @Override
        public StopOutcome requestStopAndPoll() {
            return ClientPickupWorkflowPort.requestStopAndPoll(
                    engine::requestStop,
                    engine::pollStop
            );
        }

        @Override
        public StopOutcome pollStop() {
            return engine.pollStop();
        }
    }

    private record WaitingAttempt(
            PickupAutomationPlan plan,
            boolean customRegions,
            Optional<Instant> lastProbeAt,
            Optional<RegionItemProbe.Observation> lastObservation
    ) implements WorkflowEntry {
        private WaitingAttempt(PickupAutomationPlan plan, boolean customRegions) {
            this(plan, customRegions, Optional.empty(), Optional.empty());
        }

        @Override
        public StopOutcome requestStopAndPoll() {
            return StopOutcome.CLEAN;
        }

        @Override
        public StopOutcome pollStop() {
            return StopOutcome.CLEAN;
        }

        private AutomationOperationResult result() {
            return waitingResult(lastObservation.orElse(
                    RegionItemProbe.Observation.UNOBSERVABLE
            ));
        }
    }

    static StopOutcome requestStopAndPoll(
            Runnable requestStop,
            Supplier<StopOutcome> pollStop
    ) {
        Objects.requireNonNull(requestStop, "requestStop").run();
        return Objects.requireNonNull(pollStop, "pollStop").get();
    }
}
