package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.ContainerWithdrawalResult;
import java.util.Map;
import java.util.Set;

/** Drains a capacity-limited batch and books only transfers confirmed by the server. */
public final class ContainerBatchWithdrawal implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final ContainerTarget target;
    private final Set<String> items;
    private Map<String, Integer> withdrawn = Map.of();
    private TaskModuleResult terminal;
    private boolean started;
    private boolean inFlight;
    private boolean stopRequested;
    private boolean matchingItemsRemain;

    public ContainerBatchWithdrawal(TaskExecutionContext context, ContainerTarget target,
            Set<String> items) {
        this.context = context;
        this.target = target;
        this.items = Set.copyOf(items);
    }

    @Override
    public TaskModuleResult start() {
        if (started) {
            return tick();
        }
        started = true;
        if (stopRequested) {
            terminal = TaskModuleResult.succeeded("取物前已停止");
            return terminal;
        }
        inFlight = true;
        return handle(context.containerWithdrawals().startClassificationBatch(target, items,
                Set.of(), context.workspace().classificationItemOrder(items)));
    }

    @Override
    public TaskModuleResult tick() {
        if (!started) {
            return start();
        }
        return terminal == null ? handle(context.containerWithdrawals().pollWithdrawal()) : terminal;
    }

    public Map<String, Integer> withdrawn() {
        return withdrawn;
    }

    public boolean matchingItemsRemain() {
        return matchingItemsRemain;
    }

    private TaskModuleResult handle(ContainerWithdrawalResult result) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        withdrawn = result.withdrawn();
        matchingItemsRemain = result.matchingItemsRemain();
        context.workspace().recordCacheTransfer(target, withdrawn, true);
        context.inventory().recordWithdrawn(context.workspace().inventoryAccount(), withdrawn);
        inFlight = false;
        terminal = result.state() == ContainerTransferState.FAILED
                ? TaskModuleResult.failed(result.message())
                : TaskModuleResult.succeeded(result.message());
        return terminal;
    }

    @Override
    public void requestStop() {
        stopRequested = true;
    }

    @Override
    public boolean safeToStop() {
        return !inFlight;
    }

    @Override
    public void cancel() {
        if (inFlight) {
            context.containerWithdrawals().cancelWithdrawal();
        }
        inFlight = false;
    }
}
