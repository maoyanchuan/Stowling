package dev.stowling.client.automation;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.ContainerInteraction;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.client.interaction.ClientActionScheduler;
import dev.stowling.schedule.StopOutcome;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Tracks operation keys without persisting clicks or duplicating transfer attempts.
 */
public final class ClientThreadItemTransferPort implements ItemTransferPort {
    private static final String MANUAL_RECOVERY =
            "未找到当前进程中的容器操作；请人工确认容器状态后重新创建任务。";

    private final ItemTransferPort delegate;
    private final ClientActionScheduler scheduler;
    private final Map<String, AutomationOperationResult> attempts =
            new LinkedHashMap<>();
    private final Map<UUID, StopOutcome> stopOutcomes = new LinkedHashMap<>();

    public ClientThreadItemTransferPort(
            ContainerInteraction containerInteraction,
            ClientActionScheduler scheduler
    ) {
        Objects.requireNonNull(containerInteraction, "containerInteraction");
        this.delegate = containerInteraction instanceof ItemTransferPort transferPort
                ? transferPort
                : null;
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
                    "容器操作只能在 Minecraft 客户端线程启动。"
            );
        }
        if (delegate == null) {
            return AutomationOperationResult.paused(
                    "当前容器交互尚未提供物品转移接口；请人工完成该操作后继续。"
            );
        }
        AutomationOperationResult result = Objects.requireNonNull(
                delegate.start(operationKey, request),
                "transfer start result"
        );
        UUID operationTaskId = taskId(operationKey);
        if (operationTaskId != null) {
            stopOutcomes.remove(operationTaskId);
        }
        retain(operationKey, result);
        return result;
    }

    @Override
    public synchronized AutomationOperationResult poll(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        if (!attempts.containsKey(operationKey)) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        if (!scheduler.isClientThread()) {
            return AutomationOperationResult.paused(
                    "容器操作只能在 Minecraft 客户端线程轮询。"
            );
        }
        if (delegate == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        AutomationOperationResult result = Objects.requireNonNull(
                delegate.poll(operationKey),
                "transfer poll result"
        );
        retain(operationKey, result);
        return result;
    }

    @Override
    public synchronized AutomationOperationResult reconcile(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        if (!attempts.containsKey(operationKey)) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        if (!scheduler.isClientThread()) {
            return AutomationOperationResult.paused(
                    "容器操作只能在 Minecraft 客户端线程恢复。"
            );
        }
        if (delegate == null) {
            return AutomationOperationResult.paused(MANUAL_RECOVERY);
        }
        AutomationOperationResult result = Objects.requireNonNull(
                delegate.reconcile(operationKey),
                "transfer reconcile result"
        );
        retain(operationKey, result);
        return result;
    }

    @Override
    public synchronized StopOutcome requestStop(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (!scheduler.isClientThread() || delegate == null) {
            return StopOutcome.UNSAFE;
        }
        List<String> keys = matchingKeys(attemptId);
        if (keys.isEmpty()) {
            return stopOutcomes.getOrDefault(attemptId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = delegate.requestStop(attemptId);
        if (outcome == StopOutcome.CLEAN || outcome == StopOutcome.UNSAFE) {
            keys.forEach(attempts::remove);
        }
        return retainStopOutcome(attemptId, outcome);
    }

    @Override
    public synchronized StopOutcome pollStop(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (!scheduler.isClientThread() || delegate == null) {
            return StopOutcome.UNSAFE;
        }
        List<String> keys = matchingKeys(attemptId);
        if (keys.isEmpty()) {
            return stopOutcomes.getOrDefault(attemptId, StopOutcome.UNSAFE);
        }
        StopOutcome outcome = delegate.pollStop(attemptId);
        if (outcome == StopOutcome.CLEAN || outcome == StopOutcome.UNSAFE) {
            keys.forEach(attempts::remove);
        }
        return retainStopOutcome(attemptId, outcome);
    }

    private void retain(
            String operationKey,
            AutomationOperationResult result
    ) {
        switch (result.status()) {
            case RUNNING, PAUSED, FAILED -> attempts.put(operationKey, result);
            case SUCCEEDED -> attempts.remove(operationKey);
        }
    }

    private List<String> matchingKeys(UUID taskId) {
        List<String> result = new ArrayList<>();
        for (String key : attempts.keySet()) {
            if (taskId.equals(taskId(key))) {
                result.add(key);
            }
        }
        return result;
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

    private StopOutcome retainStopOutcome(UUID taskId, StopOutcome outcome) {
        if (outcome != StopOutcome.PENDING) {
            stopOutcomes.put(taskId, outcome);
            if (stopOutcomes.size() > 128) {
                UUID oldest = stopOutcomes.keySet().iterator().next();
                stopOutcomes.remove(oldest);
            }
        }
        return outcome;
    }
}
