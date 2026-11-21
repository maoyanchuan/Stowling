package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerTarget;
import dev.stowling.v2.model.CacheIntakePolicy;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import dev.stowling.v2.runtime.TaskFlowExecutor;
import java.util.List;

/** Handles cache pressure inside collection so a background sort cannot be starved. */
final class ManageCacheIntakeModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final CacheIntakePolicy policy;
    private final String classificationRef;
    private final List<ContainerTarget> caches;
    private TaskModuleExecutor action;
    private TaskFlowExecutor classification;
    private TaskRunWorkspace sortingWorkspace;
    private TaskModuleResult terminal;
    private int phase;
    private boolean pressure;
    private boolean packed;
    private boolean stopping;

    ManageCacheIntakeModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        policy = new CacheIntakePolicy(CacheIntakePolicy.Mode.valueOf(
                step.parameters().get("cacheIntakeMode")),
                Integer.parseInt(step.parameters().get("cacheThresholdPercent")));
        classificationRef = step.parameters().getOrDefault("classificationTaskRef", "");
        caches = context.workspace().containerScan().orElseThrow().containers();
    }

    @Override
    public TaskModuleResult start() {
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (terminal != null) return terminal;
        if (stopping && safeToStop()) {
            closeSortingAccount();
            return TaskModuleResult.succeeded("缓存处理已安全停止");
        }
        if (classification != null) {
            TaskModuleResult result = classification.tick();
            if (result.state() == TaskModuleState.SUCCEEDED) {
                closeSortingAccount();
                classification = null;
                context.workspace().invalidateCacheCapacity(caches);
                phase = 3;
                action = context.storageAutomation().storeCacheIntake(context, caches, false);
            } else if (result.state() == TaskModuleState.FAILED) {
                closeSortingAccount();
                terminal = result;
            }
            return result.state() == TaskModuleState.SUCCEEDED
                    ? TaskModuleResult.waiting("分类完成，继续存入本批物品") : result;
        }
        if (action == null) {
            var unread = caches.stream()
                    .filter(cache -> !context.workspace().hasCacheCapacity(cache)).toList();
            if (unread.isEmpty()) return beginIntake();
            action = context.storageAutomation().scanCacheContents(context, unread);
        }
        TaskModuleResult result = action.tick();
        if (result.state() == TaskModuleState.FAILED) {
            terminal = result;
            return result;
        }
        if (result.state() != TaskModuleState.SUCCEEDED) return result;
        if (phase == 0) {
            return beginIntake();
        } else if (phase == 1 || phase == 2) {
            boolean remaining = context.workspace().hasManagedItems();
            boolean classify = remaining || pressure
                    && policy.mode() == CacheIntakePolicy.Mode.CLASSIFY_FIRST;
            if (!classify) return finish("本批物品已存入缓存");
            if (classificationRef.isBlank()) {
                if (!remaining) return finish("未启用分类，本批物品已存入缓存");
                terminal = TaskModuleResult.failed("缓存容量不足，请启用分类或腾出缓存空间");
                return terminal;
            }
            if (remaining && !packed) {
                packed = true;
                phase = 2;
                action = context.storageAutomation().storeCacheIntake(context, caches, true);
            } else {
                beginClassification();
            }
        } else {
            if (context.workspace().hasManagedItems()) {
                terminal = TaskModuleResult.failed("分类后缓存仍无法接收本批物品，请扩容或清理仓库");
                return terminal;
            }
            return finish("已腾出缓存空间并存入本批物品");
        }
        return TaskModuleResult.waiting(pressure ? "缓存达到容量阈值，正在处理投料"
                : "正在存入缓存");
    }

    private TaskModuleResult beginIntake() {
        pressure = context.workspace().cacheAtThreshold(caches, policy.thresholdPercent());
        packed = pressure && policy.mode() == CacheIntakePolicy.Mode.PACK_FIRST;
        phase = 1;
        action = context.storageAutomation().storeCacheIntake(context, caches, packed);
        return TaskModuleResult.waiting(pressure ? "缓存达到容量阈值，正在处理投料" : "正在存入缓存");
    }

    private void beginClassification() {
        var task = context.workspace().task(ResourceReference.parse(classificationRef).uuid());
        if (task == null) throw new IllegalStateException("缓存分类任务不存在");
        sortingWorkspace = new TaskRunWorkspace();
        sortingWorkspace.configureCleanup(context.workspace().cleanupPolicy());
        sortingWorkspace.setDiagnostics(context.workspace().diagnostics());
        TaskExecutionContext sorting = new TaskExecutionContext(context.navigation(),
                context.droppedItems(), context.containerRegions(), context.containerTransfers(),
                context.containerWithdrawals(), context.containerInspections(),
                context.categoryMarkerCache(), context.foodConsumption(), context.categoryZonesPort(),
                context.coordinates(), context.regions(), context.categoryZones(), sortingWorkspace,
                context.inventory(), context.storageAutomation());
        classification = new TaskFlowExecutor(task.steps(), sorting, new TaskFlowExecutor.Host() {
            @Override
            public TaskModuleExecutor createAction(TaskStep step) {
                return StandardTaskModuleFactories.create().stream()
                        .filter(factory -> factory.moduleId().equals(step.moduleId()))
                        .findFirst().orElseThrow().create(step, sorting);
            }

            @Override
            public void createTask(String taskRef, TaskPriority priority) {
                throw new IllegalStateException("缓存分类不能递归创建任务");
            }
        }, false);
        action = null;
    }

    private TaskModuleResult finish(String message) {
        context.workspace().containerScanRegionId().ifPresent(id ->
                context.workspace().recordCacheUpdate(id, 1));
        terminal = TaskModuleResult.succeeded(message);
        return terminal;
    }

    private void closeSortingAccount() {
        if (sortingWorkspace == null) return;
        if (classification != null && !classification.safeToStop()) return;
        context.storageAutomation().adoptTemporaryResources(
                sortingWorkspace.inventoryAccount().ownerId(),
                context.workspace().inventoryAccount().ownerId());
        context.inventory().transferManaged(sortingWorkspace.inventoryAccount(),
                context.workspace().inventoryAccount());
        context.inventory().closeAccount(sortingWorkspace.inventoryAccount());
        sortingWorkspace = null;
    }

    @Override
    public void requestStop() {
        stopping = true;
        if (classification != null) classification.requestStop();
        else if (action != null) action.requestStop();
    }

    @Override
    public boolean safeToStop() {
        return classification != null ? classification.safeToStop()
                : action == null || action.safeToStop();
    }

    @Override
    public void cancel() {
        requestStop();
        if (action != null) action.cancel();
        if (safeToStop()) closeSortingAccount();
    }
}
