package dev.stowling.client.automation;

import dev.stowling.automation.AutomationOperationResult;
import dev.stowling.automation.CraftingPort;
import dev.stowling.schedule.StopOutcome;
import java.util.Objects;
import java.util.UUID;

public final class UnsupportedClientCraftingPort implements CraftingPort {
    private static final String LIMITATION =
            "尚未提供安全的客户端制作与服务器确认流程；请人工完成制作后继续。";

    @Override
    public AutomationOperationResult start(String operationKey, Request request) {
        Objects.requireNonNull(operationKey, "operationKey");
        Objects.requireNonNull(request, "request");
        return AutomationOperationResult.paused(LIMITATION);
    }

    @Override
    public AutomationOperationResult poll(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        return AutomationOperationResult.paused(LIMITATION);
    }

    @Override
    public AutomationOperationResult reconcile(String operationKey) {
        Objects.requireNonNull(operationKey, "operationKey");
        return AutomationOperationResult.paused(LIMITATION);
    }

    @Override
    public StopOutcome requestStop(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        return StopOutcome.CLEAN;
    }

    @Override
    public StopOutcome pollStop(UUID attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        return StopOutcome.CLEAN;
    }
}
