package dev.stowling.client.automation;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.PackingDisposition;
import dev.stowling.automation.PackingOperationPort;
import dev.stowling.automation.PackingPort;
import dev.stowling.automation.PackingRequest;
import dev.stowling.automation.TaskPackingPlan;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.schedule.StopOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Keeps each task packing operation on one independent packing task UUID.
 */
public final class ClientTaskPackingPort implements PackingOperationPort {
    private static final String MANUAL_RECOVERY =
            "未找到当前进程中的打包操作；请人工确认站点状态后重新创建任务。";
    private static final String CONFIRMED_ACTION =
            "PACK_ITEMS confirmed by the packing disposition";

    private final PackingPort packing;
    private final ClientActionScheduler scheduler;
    private final Map<String, Operation> operations = new LinkedHashMap<>();
    private final Map<UUID, StopOutcome> stopOutcomes = new LinkedHashMap<>();

    public ClientTaskPackingPort(
            PackingPort packing,
            ClientActionScheduler scheduler
    ) {
        this.packing = Objects.requireNonNull(packing, "packing");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    @Override
    public synchronized AutomationOperationResult start(
            String operationKey,
            Request request
    ) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(request, "request");
        Operation existing = operations.get(operationKey);
        if (existing != null) {
            return pollOperation(existing);
        }
        if (!scheduler.isClientThread()) {
            return AutomationOperationResult.paused(
                    "打包操作只能在 Minecraft 客户端线程启动。"
            );
        }
        TaskPackingPlan plan = request.plan();
        UUID packingTaskId = UUID.randomUUID();
        PackingRequest packingRequest = new PackingRequest(
                packingTaskId,
                request.source(),
                plan.route().id(),
                plan.stations().stream().map(station -> station.id()).toList(),
                request.items(),
                Optional.of(plan)
        );
        UUID parentTaskId = taskId(operationKey);
        Operation operation = new Operation(
                operationKey,
                parentTaskId,
                packingTaskId,
                packingRequest
        );
        operations.put(operationKey, operation);
        if (parentTaskId != null) {
            stopOutcomes.remove(parentTaskId);
        }
        PackingDisposition disposition = Objects.requireNonNull(
                packing.submit(packingRequest),
                "packing start disposition"
        );
        return apply(operation, disposition);
    }

    @Override
    public synchronized AutomationOperationResult poll(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        Operation operation = operations.get(operationKey);
        return pollOperation(operation);
    }

    @Override
    public synchronized AutomationOperationResult reconcile(
            String operationKey
    ) {
        Objects.requireNonNull(operationKey, "operationKey");
        Operation operation = operations.get(operationKey);
        return pollOperation(operation);
    }

    @Override
    public synchronized StopOutcome requestStop(UUID parentTaskId) {
        Objects.requireNonNull(parentTaskId, "parentTaskId");
        if (!scheduler.isClientThread()) {
            return StopOutcome.UNSAFE;
        }
        List<Operation> matches = matchingOperations(parentTaskId);
        if (matches.isEmpty()) {
            return stopOutcomes.getOrDefault(parentTaskId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = StopOutcome.CLEAN;
        for (Operation operation : matches) {
            outcome = combine(
                    outcome,
                    packing.requestStop(operation.packingTaskId())
            );
        }
        removeTerminal(matches, outcome);
        return retainStopOutcome(parentTaskId, outcome);
    }

    @Override
    public synchronized StopOutcome pollStop(UUID parentTaskId) {
        Objects.requireNonNull(parentTaskId, "parentTaskId");
        if (!scheduler.isClientThread()) {
            return StopOutcome.UNSAFE;
        }
        List<Operation> matches = matchingOperations(parentTaskId);
        if (matches.isEmpty()) {
            return stopOutcomes.getOrDefault(parentTaskId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = StopOutcome.CLEAN;
        for (Operation operation : matches) {
            outcome = combine(
                    outcome,
                    packing.pollStop(operation.packingTaskId())
            );
        }
        removeTerminal(matches, outcome);
        return retainStopOutcome(parentTaskId, outcome);
    }

    private AutomationOperationResult pollOperation(Operation operation) {
        if (operation == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        if (!scheduler.isClientThread()) {
            return AutomationOperationResult.paused(
                    "打包操作只能在 Minecraft 客户端线程轮询。"
            );
        }
        PackingDisposition disposition = Objects.requireNonNull(
                packing.poll(operation.packingRequest()),
                "packing poll disposition"
        );
        return apply(operation, disposition);
    }

    private AutomationOperationResult apply(
            Operation operation,
            PackingDisposition disposition
    ) {
        return switch (disposition) {
            case PENDING -> AutomationOperationResult.running(
                    "packing operation remains pending"
            );
            case ACCEPTED -> {
                operations.remove(operation.operationKey());
                rememberTerminal(operation.parentTaskId(), StopOutcome.CLEAN);
                yield AutomationOperationResult.succeeded(CONFIRMED_ACTION);
            }
            case UNAVAILABLE -> {
                operations.remove(operation.operationKey());
                rememberTerminal(operation.parentTaskId(), StopOutcome.UNSAFE);
                yield AutomationOperationResult.failed(
                        "打包操作状态已丢失；请人工确认站点状态后重新创建任务。"
                );
            }
        };
    }

    private List<Operation> matchingOperations(UUID parentTaskId) {
        List<Operation> matches = new ArrayList<>();
        for (Operation operation : operations.values()) {
            if (parentTaskId.equals(operation.parentTaskId())) {
                matches.add(operation);
            }
        }
        return matches;
    }

    private void removeTerminal(
            List<Operation> matches,
            StopOutcome outcome
    ) {
        if (outcome == StopOutcome.CLEAN || outcome == StopOutcome.UNSAFE) {
            matches.forEach(operation ->
                    operations.remove(operation.operationKey()));
        }
    }

    private void rememberTerminal(UUID parentTaskId, StopOutcome outcome) {
        if (parentTaskId != null) {
            retainStopOutcome(parentTaskId, outcome);
        }
    }

    private StopOutcome retainStopOutcome(UUID parentTaskId, StopOutcome outcome) {
        if (outcome != StopOutcome.PENDING) {
            stopOutcomes.put(parentTaskId, outcome);
            if (stopOutcomes.size() > 128) {
                UUID oldest = stopOutcomes.keySet().iterator().next();
                stopOutcomes.remove(oldest);
            }
        }
        return outcome;
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

    private record Operation(
            String operationKey,
            UUID parentTaskId,
            UUID packingTaskId,
            PackingRequest packingRequest
    ) {
    }
}
