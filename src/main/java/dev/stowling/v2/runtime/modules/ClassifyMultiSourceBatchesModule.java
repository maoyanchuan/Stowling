package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ItemIdentity;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** One explicit cycle owns multi-source filling, ordered delivery and returning the remainder. */
final class ClassifyMultiSourceBatchesModule implements TaskModuleExecutor {
    private enum Phase {
        FILL, WITHDRAW, SELECT, MOVE, DEPOSIT, RETURN
    }

    private final TaskExecutionContext context;
    private final Deque<ContainerTarget> pending = new ArrayDeque<>();
    private final Set<ContainerTarget> visited = new LinkedHashSet<>();
    private final Deque<ContainerTarget> returns = new ArrayDeque<>();
    private List<ContainerTarget> sources = List.of();
    private ContainerTarget source;
    private ContainerTarget target;
    private Map<String, Integer> before = Map.of();
    private Phase phase = Phase.FILL;
    private TaskModuleExecutor active;
    private boolean activeStarted;
    private boolean started;
    private boolean stopRequested;
    private TaskModuleResult terminal;

    ClassifyMultiSourceBatchesModule(TaskExecutionContext context) {
        this.context = context;
    }

    @Override
    public TaskModuleResult start() {
        if (!started) {
            started = true;
            if (!context.workspace().classificationBaselineEnabled()) {
                return fail("分类取料与交付顺序调试开关未启用");
            }
            sources = context.workspace().cacheContainers();
            pending.addAll(sources);
            if (!sources.isEmpty()) {
                context.workspace().selectClassificationSource(sources.getFirst());
            }
        }
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (!started) return start();
        if (terminal != null) return terminal;
        if (active == null) {
            if (stopRequested) return finish("分类批次已安全停止，等待任务收尾");
            return phase == Phase.RETURN ? nextReturn() : fill();
        }
        TaskModuleResult result;
        try {
            boolean first = !activeStarted;
            activeStarted = true;
            result = first ? active.start() : active.tick();
        } catch (RuntimeException exception) {
            result = TaskModuleResult.failed(exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage());
        }
        if (result.state() != TaskModuleState.SUCCEEDED
                && result.state() != TaskModuleState.FAILED) return result;
        if (!active.safeToStop()) return fail("分类存取尚未确认：" + result.message());
        if (result.state() == TaskModuleState.FAILED
                && context.storageAutomation().hasTemporaryResources(
                        context.workspace().inventoryAccount().ownerId())) {
            return fail("分类资源尚未回收，交给任务收尾：" + result.message());
        }
        active = null;
        if (stopRequested) return finish("分类存取已确认，等待任务收尾");
        return switch (phase) {
            case WITHDRAW -> afterWithdrawal(result);
            case SELECT -> afterSelection(result);
            case MOVE -> afterMove(result);
            case DEPOSIT -> afterDeposit(result);
            case RETURN -> afterReturn(result);
            case FILL -> fail("分类批次阶段无效");
        };
    }

    private TaskModuleResult fill() {
        if (pending.isEmpty() || visited.contains(pending.peekFirst())
                || !context.workspace().carriedItems().isEmpty() && !hasRoom()) {
            if (context.workspace().carriedItems().isEmpty()) {
                if (pending.isEmpty()) return finish("当前分类区已完成："
                        + context.workspace().classificationSummary());
                visited.clear();
                return TaskModuleResult.running("继续访问尚有分类物品的来源");
            }
            context.workspace().resetStorageSelection();
            return select();
        }
        source = pending.removeFirst();
        visited.add(source);
        before = context.workspace().carriedItems();
        context.workspace().selectClassificationSource(source);
        return run(Phase.WITHDRAW, new WithdrawItemsModule(context,
                action(ConfigValidator.WITHDRAW_ITEMS, "container", "classified")));
    }

    private boolean hasRoom() {
        var observation = context.inventory().observe().orElseThrow(() ->
                new IllegalStateException("无法读取分类取料前的真实背包"));
        var snapshot = observation.snapshot();
        if (snapshot.slots().isEmpty()) return true;
        long empty = snapshot.slots().stream()
                .filter(slot -> !snapshot.lockedSlots().contains(slot.index()))
                .filter(slot -> slot.count() == 0).count();
        // Matches the withdrawal port's existing two-slot packing/recovery reservation.
        return empty > 2;
    }

    private TaskModuleResult afterWithdrawal(TaskModuleResult result) {
        boolean progress = !before.equals(context.workspace().carriedItems());
        if (result.state() == TaskModuleState.FAILED) {
            context.workspace().recordClassificationSourceIssue(source, result.message());
        } else if (context.workspace().cacheHasMore()
                && (progress || !context.workspace().carriedItems().isEmpty())) {
            pending.addLast(source);
        } else if (context.workspace().cacheHasMore() && !progress
                && context.workspace().carriedItems().isEmpty()) {
            context.workspace().recordClassificationIssue(source, "背包或打包资源不足，未能取料");
        }
        phase = Phase.FILL;
        return TaskModuleResult.running("继续多来源取料，已有货物保留在本批");
    }

    private TaskModuleResult select() {
        if (context.workspace().carriedItems().isEmpty()) return nextBatch();
        return run(Phase.SELECT, new SelectStorageTargetModule(context,
                TaskStep.action(UUID.randomUUID(), ConfigValidator.SELECT_STORAGE_TARGET,
                        Map.of(ConfigValidator.ALLOW_CACHE_FALLBACK, "false"))));
    }

    private TaskModuleResult afterSelection(TaskModuleResult result) {
        target = context.workspace().selectedStorageTarget().orElse(null);
        if (target == null) return beginReturn();
        if (result.state() == TaskModuleState.FAILED) return blocked(result.message());
        return run(Phase.MOVE, new MoveToCoordinateModule(context,
                action(ConfigValidator.MOVE_TO_COORDINATE, "storage", "carried")));
    }

    private TaskModuleResult afterMove(TaskModuleResult result) {
        if (result.state() == TaskModuleState.FAILED
                || context.workspace().isRejectedStorage(target)) {
            return blocked(result.message());
        }
        return run(Phase.DEPOSIT, new DepositItemsActionModule(context,
                action(ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS, "storage", "carried")));
    }

    private TaskModuleResult afterDeposit(TaskModuleResult result) {
        if (result.state() == TaskModuleState.FAILED) return blocked(result.message());
        Set<String> accepted = context.workspace().acceptedItemsByContainer()
                .getOrDefault(target, Set.of());
        if (context.workspace().carriedItems().keySet().stream()
                .anyMatch(key -> ItemIdentity.matches(key, accepted))) {
            return blocked("目标未能接收本批全部余量；容量由剩余处理阶段另行确认");
        }
        return select();
    }

    private TaskModuleResult blocked(String reason) {
        context.workspace().markTargetUnavailable(target, reason);
        context.workspace().recordClassificationIssue(target, reason);
        return select();
    }

    private TaskModuleResult beginReturn() {
        returns.clear();
        returns.addAll(visited);
        for (ContainerTarget candidate : sources) {
            if (!returns.contains(candidate)) returns.addLast(candidate);
        }
        phase = Phase.RETURN;
        return TaskModuleResult.running("本批目标已遍历，将余物归还物品来源");
    }

    private TaskModuleResult nextReturn() {
        if (context.workspace().carriedItems().isEmpty()) return nextBatch();
        if (returns.isEmpty()) return fail("本批余物无法归还物品来源，交给任务中断收尾");
        source = returns.removeFirst();
        context.workspace().selectClassificationSource(source);
        return run(Phase.RETURN, new DepositItemsActionModule(context,
                action(ConfigValidator.DEPOSIT_ITEMS_TO_CONTAINERS, "container", "carried")));
    }

    private TaskModuleResult afterReturn(TaskModuleResult result) {
        if (result.state() == TaskModuleState.FAILED) {
            context.workspace().recordClassificationIssue(source, result.message());
        }
        return TaskModuleResult.running("核对归还余物，继续处理剩余来源");
    }

    private TaskModuleResult nextBatch() {
        visited.clear();
        phase = Phase.FILL;
        return TaskModuleResult.running("本批交付与余物归还结束，继续下一批");
    }

    private TaskModuleResult run(Phase next, TaskModuleExecutor executor) {
        phase = next;
        active = executor;
        activeStarted = false;
        return TaskModuleResult.running(switch (next) {
            case FILL, WITHDRAW -> "正在从多个来源取料";
            case SELECT -> "正在选择下一分类目标";
            case MOVE -> "正在前往分类目标";
            case DEPOSIT -> "正在交付分类批次";
            case RETURN -> "正在归还本批余物";
        });
    }

    private static TaskStep action(String module, String output, String source) {
        return TaskStep.action(UUID.randomUUID(), module,
                Map.of(ConfigValidator.TARGET_REF, ResourceReference.output(output).encode(),
                        ConfigValidator.ITEM_SOURCE, source,
                        ConfigValidator.RETRY_ON_FAILURE, "false"));
    }

    private TaskModuleResult finish(String message) {
        terminal = TaskModuleResult.succeeded(message);
        return terminal;
    }

    private TaskModuleResult fail(String message) {
        terminal = TaskModuleResult.failed(message);
        return terminal;
    }

    @Override
    public void requestStop() {
        stopRequested = true;
        if (active != null) active.requestStop();
    }

    @Override
    public boolean safeToStop() {
        return active == null || active.safeToStop();
    }

    @Override
    public void cancel() {
        if (active != null) active.cancel();
        active = null;
    }
}
