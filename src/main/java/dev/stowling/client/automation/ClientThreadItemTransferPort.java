package dev.stowling.client.automation;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.ContainerInteraction;
import dev.stowling.automation.ItemTransferPort;
import dev.stowling.client.interaction.ClientActionScheduler;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

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

    private void retain(
            String operationKey,
            AutomationOperationResult result
    ) {
        switch (result.status()) {
            case RUNNING, PAUSED -> attempts.put(operationKey, result);
            case SUCCEEDED, FAILED -> attempts.remove(operationKey);
        }
    }
}
