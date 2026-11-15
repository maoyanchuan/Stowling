package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerCategory;
import dev.stowling.automation.ContainerCategoryResult;
import dev.stowling.automation.ContainerScanResult;
import dev.stowling.automation.ContainerTarget;
import dev.stowling.automation.ContainerTransferResult;
import dev.stowling.automation.ContainerTransferState;
import dev.stowling.automation.ContainerWithdrawalResult;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SortCacheItemsModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final RegionDefinition cacheRegion;
    private final Set<String> containerTypes;
    private final int maxVolume;
    private final Map<ContainerTarget, String> categories =
            new LinkedHashMap<>();
    private final Set<String> categoryItemIds;
    private final List<ContainerTarget> cacheTargets;
    private final List<ContainerTarget> targetContainers;
    private final String setupError;
    private final Map<String, Integer> carried = new LinkedHashMap<>();
    private int cacheIndex;
    private int storageIndex;
    private ContainerTarget activeCache;
    private ContainerTarget activeStorage;
    private boolean cacheNavigationStarted;
    private boolean storageNavigationStarted;
    private boolean withdrawalActive;
    private boolean depositActive;
    private boolean cacheHasMore;
    private boolean returning;
    private String returnReason = "";

    SortCacheItemsModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.cacheRegion = TaskModuleSupport.region(
                context,
                step,
                ConfigValidator.CACHE_REGION_ID
        );
        this.containerTypes = TaskModuleSupport.containerTypes(step);
        this.maxVolume = TaskModuleSupport.integerParameter(
                step,
                ConfigValidator.MAX_VOLUME,
                TaskModuleSupport.DEFAULT_SCAN_VOLUME
        );
        CategorySetup setup = loadCategories();
        this.categoryItemIds = setup.categoryItemIds();
        this.targetContainers = setup.targetContainers();
        this.cacheTargets = setup.cacheTargets();
        this.setupError = setup.error();
    }

    @Override
    public TaskModuleResult start() {
        if (!setupError.isEmpty()) {
            return TaskModuleResult.failed(setupError);
        }
        if (categories.isEmpty()) {
            return TaskModuleResult.failed("没有可识别分类的目标容器");
        }
        if (cacheTargets.isEmpty()) {
            return TaskModuleResult.succeeded("缓存区域没有容器");
        }
        return tick();
    }

    @Override
    public TaskModuleResult tick() {
        if (returning) {
            return tickReturnToCache();
        }
        if (!carried.isEmpty()) {
            return tickDistribution();
        }
        if (cacheIndex >= cacheTargets.size()) {
            return TaskModuleResult.succeeded("缓存中的可分类物品已处理");
        }
        activeCache = cacheTargets.get(cacheIndex);
        NavigationStatus navigation = navigateToCache();
        if (navigation != NavigationStatus.ARRIVED) {
            return navigationResult(navigation, "正在前往缓存容器");
        }
        ContainerWithdrawalResult result;
        if (!withdrawalActive) {
            withdrawalActive = true;
            result = context.containerWithdrawals().startWithdrawal(
                    activeCache,
                    categoryItemIds
            );
        } else {
            result = context.containerWithdrawals().pollWithdrawal();
        }
        return handleWithdrawal(result);
    }

    @Override
    public void cancel() {
        context.containerWithdrawals().cancelWithdrawal();
        context.containerTransfers().cancelDeposit();
        context.navigation().cancel();
    }

    private CategorySetup loadCategories() {
        ContainerScanResult targetScan = context.workspace().containerScan()
                .orElse(null);
        ContainerCategoryResult categoryResult = context.workspace()
                .containerCategories()
                .orElse(null);
        if (targetScan == null) {
            return CategorySetup.failure("缺少上一次目标容器扫描结果");
        }
        if (!targetScan.succeeded()) {
            return CategorySetup.failure(targetScan.message());
        }
        if (categoryResult == null) {
            return CategorySetup.failure("缺少上一次容器分类结果");
        }
        if (!categoryResult.succeeded()) {
            return CategorySetup.failure(categoryResult.message());
        }
        for (ContainerCategory category : categoryResult.categories()) {
            categories.put(category.container(), category.itemId());
        }
        ContainerScanResult cacheScan = context.containerRegions().scan(
                cacheRegion,
                containerTypes,
                maxVolume
        );
        if (!cacheScan.succeeded()) {
            return CategorySetup.failure(cacheScan.message());
        }
        Set<ContainerTarget> targetSet = Set.copyOf(targetScan.containers());
        List<ContainerTarget> cacheContainers = cacheScan.containers().stream()
                .filter(target -> !targetSet.contains(target))
                .toList();
        if (cacheContainers.size() != cacheScan.containers().size()) {
            return CategorySetup.failure("缓存区域与目标区域不能重叠");
        }
        return new CategorySetup(
                Set.copyOf(categories.values()),
                List.copyOf(categories.keySet()),
                cacheContainers,
                ""
        );
    }

    private NavigationStatus navigateToCache() {
        if (!cacheNavigationStarted) {
            cacheNavigationStarted = true;
            return context.navigation().start(activeCache.position());
        }
        return context.navigation().status(activeCache.position(), 2);
    }

    private NavigationStatus navigateToStorage() {
        if (!storageNavigationStarted) {
            storageNavigationStarted = true;
            return context.navigation().start(activeStorage.position());
        }
        return context.navigation().status(activeStorage.position(), 2);
    }

    private TaskModuleResult handleWithdrawal(
            ContainerWithdrawalResult result
    ) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        withdrawalActive = false;
        carried.putAll(result.withdrawn());
        cacheHasMore = result.matchingItemsRemain();
        if (result.state() == ContainerTransferState.FAILED) {
            if (!carried.isEmpty()) {
                beginReturnToCache(result.message());
                return TaskModuleResult.waiting(
                        result.message() + "，正在退回缓存"
                );
            }
            return TaskModuleResult.failed(result.message());
        }
        if (carried.isEmpty()) {
            if (cacheHasMore) {
                return TaskModuleResult.failed(
                        "缓存中有匹配物品，但玩家背包空间不足"
                );
            }
            finishCurrentCache();
            return TaskModuleResult.running("当前缓存容器没有匹配物品");
        }
        storageIndex = 0;
        activeStorage = null;
        return tickDistribution();
    }

    private TaskModuleResult tickDistribution() {
        if (activeStorage == null) {
            if (!selectStorageTarget()) {
                beginReturnToCache("目标分类容器空间不足");
                return TaskModuleResult.waiting(
                        "目标分类容器空间不足，正在退回缓存"
                );
            }
        }
        NavigationStatus navigation = navigateToStorage();
        if (navigation != NavigationStatus.ARRIVED) {
            return navigationResult(navigation, "正在前往分类容器");
        }
        ContainerTransferResult result;
        String category = categories.get(activeStorage);
        Map<String, Integer> request = carried.containsKey(category)
                ? Map.of(category, carried.get(category))
                : Map.of();
        if (!depositActive) {
            depositActive = true;
            result = context.containerTransfers().startDeposit(
                    activeStorage,
                    request
            );
        } else {
            result = context.containerTransfers().pollDeposit();
        }
        return handleDeposit(result, category);
    }

    private boolean selectStorageTarget() {
        while (storageIndex < targetContainers.size()) {
            ContainerTarget candidate = targetContainers.get(storageIndex);
            String category = categories.get(candidate);
            if (carried.getOrDefault(category, 0) > 0) {
                activeStorage = candidate;
                storageNavigationStarted = false;
                depositActive = false;
                return true;
            }
            storageIndex++;
        }
        return false;
    }

    private TaskModuleResult handleDeposit(
            ContainerTransferResult result,
            String category
    ) {
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        depositActive = false;
        int remaining = result.remaining().getOrDefault(category, 0);
        if (remaining > 0) {
            carried.put(category, remaining);
        } else {
            carried.remove(category);
        }
        activeStorage = null;
        storageNavigationStarted = false;
        storageIndex++;
        if (carried.isEmpty()) {
            if (cacheHasMore) {
                cacheHasMore = false;
                cacheNavigationStarted = false;
                return tick();
            }
            finishCurrentCache();
            return TaskModuleResult.running("已完成当前缓存容器分类");
        }
        if (storageIndex >= targetContainers.size()) {
            beginReturnToCache("目标分类容器空间不足");
            return TaskModuleResult.waiting(
                    "目标分类容器空间不足，正在退回缓存"
            );
        }
        return tickDistribution();
    }

    private void beginReturnToCache(String reason) {
        returning = true;
        returnReason = reason;
        activeStorage = null;
        cacheNavigationStarted = false;
        storageNavigationStarted = false;
        depositActive = false;
    }

    private TaskModuleResult tickReturnToCache() {
        if (carried.isEmpty()) {
            returning = false;
            finishCurrentCache();
            return TaskModuleResult.failed(returnReason);
        }
        NavigationStatus navigation = navigateToCache();
        if (navigation != NavigationStatus.ARRIVED) {
            return navigationResult(navigation, "正在返回缓存容器");
        }
        ContainerTransferResult result;
        if (!depositActive) {
            depositActive = true;
            result = context.containerTransfers().startDeposit(
                    activeCache,
                    Map.copyOf(carried)
            );
        } else {
            result = context.containerTransfers().pollDeposit();
        }
        if (result.state() == ContainerTransferState.PENDING) {
            return TaskModuleResult.waiting(result.message());
        }
        depositActive = false;
        carried.clear();
        carried.putAll(result.remaining());
        if (!carried.isEmpty()) {
            return TaskModuleResult.failed(
                    returnReason + "，且有物品未能退回缓存"
            );
        }
        returning = false;
        finishCurrentCache();
        return TaskModuleResult.failed(
                returnReason + "，未存入物品已退回缓存"
        );
    }

    private void finishCurrentCache() {
        cacheIndex++;
        activeCache = null;
        cacheNavigationStarted = false;
        withdrawalActive = false;
        cacheHasMore = false;
    }

    private static TaskModuleResult navigationResult(
            NavigationStatus status,
            String navigatingMessage
    ) {
        return status == NavigationStatus.FAILED
                ? TaskModuleResult.failed("无法前往容器")
                : TaskModuleResult.running(navigatingMessage);
    }

    private record CategorySetup(
            Set<String> categoryItemIds,
            List<ContainerTarget> targetContainers,
            List<ContainerTarget> cacheTargets,
            String error
    ) {
        private static CategorySetup failure(String message) {
            return new CategorySetup(
                    Set.of(),
                    List.of(),
                    List.of(),
                    message
            );
        }
    }
}
