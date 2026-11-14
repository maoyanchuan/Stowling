package dev.stowling.automation.module;

import dev.stowling.automation.AutomationModuleContext;
import dev.stowling.automation.AutomationModuleResult;
import dev.stowling.automation.AutomationOperationPort;
import dev.stowling.automation.AutomationOperationResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class OperationModuleSupport {
    private static final String PHASE = "phase";
    private static final String OPERATION_KEY = "operationKey";
    private static final String STARTED = "STARTED";
    private static final String POLLING = "POLLING";
    private static final String RECONCILED = "RECONCILED";
    private static final String RECONCILE = "RECONCILE";
    private static final String CONFIRMED = "CONFIRMED";

    private OperationModuleSupport() {
    }

    static <Request> AutomationModuleResult execute(
            AutomationModuleContext context,
            Request request,
            AutomationOperationPort<Request> port
    ) {
        Objects.requireNonNull(port, "port");
        String expectedKey = OperationKeys.forStep(context);
        String storedKey = context.moduleState().get(OPERATION_KEY);
        if (storedKey == null) {
            return begin(context, request, port, expectedKey);
        }
        if (!storedKey.equals(expectedKey)) {
            return AutomationModuleResult.failed(
                    "持久化操作与当前步骤参数不匹配"
            );
        }
        String phase = context.moduleState().getOrDefault(PHASE, STARTED);
        AutomationOperationResult result;
        String nextPhase;
        if (context.recovered() || RECONCILE.equals(phase)) {
            result = port.reconcile(storedKey);
            nextPhase = RECONCILED;
        } else {
            result = port.poll(storedKey);
            nextPhase = POLLING;
        }
        return map(result, storedKey, nextPhase);
    }

    private static <Request> AutomationModuleResult begin(
            AutomationModuleContext context,
            Request request,
            AutomationOperationPort<Request> port,
            String operationKey
    ) {
        AutomationOperationResult result = Objects.requireNonNull(
                port.start(operationKey, request),
                "operation start result"
        );
        String phase = result.status()
                == dev.stowling.automation.AutomationOperationStatus.RUNNING
                ? STARTED
                : RECONCILE;
        return map(result, operationKey, phase);
    }

    private static AutomationModuleResult map(
            AutomationOperationResult result,
            String operationKey,
            String phase
    ) {
        Map<String, String> state = state(operationKey, phase);
        return switch (result.status()) {
            case RUNNING -> new AutomationModuleResult(
                    dev.stowling.automation.AutomationModuleStatus.RUNNING,
                    state,
                    result.lastConfirmedAction(),
                    result.message()
            );
            case SUCCEEDED -> new AutomationModuleResult(
                    dev.stowling.automation.AutomationModuleStatus.SUCCEEDED,
                    state(operationKey, CONFIRMED),
                    result.lastConfirmedAction(),
                    result.message()
            );
            case PAUSED -> new AutomationModuleResult(
                    dev.stowling.automation.AutomationModuleStatus.PAUSED,
                    state(operationKey, RECONCILE),
                    result.lastConfirmedAction(),
                    result.message()
            );
            case FAILED -> new AutomationModuleResult(
                    dev.stowling.automation.AutomationModuleStatus.FAILED,
                    state,
                    result.lastConfirmedAction(),
                    result.message()
            );
        };
    }

    private static Map<String, String> state(
            String operationKey,
            String phase
    ) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put(PHASE, phase);
        state.put(OPERATION_KEY, operationKey);
        return Map.copyOf(state);
    }
}
