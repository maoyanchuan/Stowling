package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerScanResult;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.Set;

final class ScanContainersInRegionModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final ResourceReference reference;
    private final Set<String> containerTypes;
    private final int maxVolume;
    private final boolean scanContents;
    private TaskModuleExecutor inventoryScan;

    ScanContainersInRegionModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        String location = step.parameters().get(ConfigValidator.CACHE_LOCATION_REF);
        this.reference = location != null ? ResourceReference.parse(location)
                : ResourceReferenceSupport.reference(
                step,
                TaskModuleIds.REGION_REF,
                ResourceType.REGION,
                TaskModuleIds.REGION_ID
        );
        this.containerTypes = TaskModuleSupport.containerTypes(step);
        this.maxVolume = TaskModuleSupport.integerParameter(
                step,
                TaskModuleIds.MAX_VOLUME,
                TaskModuleSupport.DEFAULT_SCAN_VOLUME
        );
        this.scanContents = TaskModuleSupport.booleanParameter(
                step, ConfigValidator.SCAN_CONTENTS, false);
    }

    @Override
    public TaskModuleResult start() {
        ContainerScanResult scan = CacheLocations.scan(
                context, reference, containerTypes, maxVolume, scanContents);
        context.workspace().refreshContainerScan(reference.uuid(), scan);
        if (!scan.succeeded()) {
            return TaskModuleResult.failed(scan.message());
        }
        if (!scanContents || scan.containers().isEmpty()) {
            return TaskModuleResult.succeeded(scan.message());
        }
        var unread = scan.containers().stream()
                .filter(target -> context.workspace().cacheContents(target).isEmpty()).toList();
        if (unread.isEmpty()) {
            return TaskModuleResult.succeeded("已复用本轮确认的缓存库存");
        }
        inventoryScan = context.storageAutomation().scanCacheContents(context, unread);
        return inventoryScan.start();
    }

    @Override
    public TaskModuleResult tick() {
        if (inventoryScan == null) {
            throw new IllegalStateException("scan module already completed");
        }
        return inventoryScan.tick();
    }

    @Override
    public void cancel() {
        if (inventoryScan != null) {
            inventoryScan.cancel();
        }
    }

    @Override
    public void requestStop() {
        if (inventoryScan != null) {
            inventoryScan.requestStop();
        }
    }

    @Override
    public boolean safeToStop() {
        return inventoryScan == null || inventoryScan.safeToStop();
    }
}
