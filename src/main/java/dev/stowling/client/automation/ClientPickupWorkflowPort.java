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
import dev.stowling.schedule.StopOutcome;
import java.time.Duration;
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
    private static final String MANUAL_RECOVERY =
            "拾取流程的进程内操作已丢失；请人工确认拾取状态后重新创建任务。";

    private final Supplier<StowlingConfig> config;
    private final PickupEngineFactory engineFactory;
    private final ClientActionScheduler scheduler;
    private final Map<String, PickupAttempt> attempts = new LinkedHashMap<>();
    private final Map<UUID, StopOutcome> stopOutcomes = new LinkedHashMap<>();

    public ClientPickupWorkflowPort(
            Supplier<StowlingConfig> config,
            PickupEngineFactory engineFactory,
            ClientActionScheduler scheduler
    ) {
        this.config = Objects.requireNonNull(config, "config");
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
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
        Optional<PickupWorkflow> workflow = loaded.pickupWorkflows().stream()
                .filter(candidate -> candidate.id().equals(request.workflowId()))
                .findFirst();
        if (workflow.isEmpty()) {
            return AutomationOperationResult.failed(
                    "未知拾取流程: " + request.workflowId()
            );
        }
        try {
            PickupAutomationEngine engine = engineFactory.create(plan(
                    taskId,
                    workflow.orElseThrow(),
                    request.regionId(),
                    loaded
            ));
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
        PickupAttempt attempt = attempts.get(operationKey);
        if (attempt == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        if (!scheduler.isClientThread()) {
            return AutomationOperationResult.paused(
                    "拾取流程只能在 Minecraft 客户端线程轮询。"
            );
        }
        return map(operationKey, attempt.engine.tick());
    }

    @Override
    public synchronized AutomationOperationResult reconcile(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        PickupAttempt attempt = attempts.get(operationKey);
        if (attempt == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        if (!scheduler.isClientThread()) {
            return AutomationOperationResult.paused(
                    "拾取流程只能在 Minecraft 客户端线程恢复。"
            );
        }
        return map(operationKey, attempt.engine.tick());
    }

    @Override
    public synchronized StopOutcome requestStop(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (!scheduler.isClientThread()) {
            return StopOutcome.UNSAFE;
        }
        List<Map.Entry<String, PickupAttempt>> matches = attemptEntries(attemptId);
        if (matches.isEmpty()) {
            return stopOutcomes.getOrDefault(attemptId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = StopOutcome.CLEAN;
        for (Map.Entry<String, PickupAttempt> entry : matches) {
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
        List<Map.Entry<String, PickupAttempt>> matches = attemptEntries(attemptId);
        if (matches.isEmpty()) {
            return stopOutcomes.getOrDefault(attemptId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = StopOutcome.CLEAN;
        for (Map.Entry<String, PickupAttempt> entry : matches) {
            StopOutcome current = entry.getValue().engine.pollStop();
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
        PickupAttempt attempt = attempts.get(operationKey);
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

    private List<Map.Entry<String, PickupAttempt>> attemptEntries(UUID taskId) {
        List<Map.Entry<String, PickupAttempt>> result = new ArrayList<>();
        for (Map.Entry<String, PickupAttempt> entry : attempts.entrySet()) {
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

    private record PickupAttempt(PickupAutomationEngine engine) {
        private StopOutcome requestStopAndPoll() {
            return ClientPickupWorkflowPort.requestStopAndPoll(
                    engine::requestStop,
                    engine::pollStop
            );
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
