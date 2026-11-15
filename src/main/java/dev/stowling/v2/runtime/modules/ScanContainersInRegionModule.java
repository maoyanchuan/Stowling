package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.ContainerScanResult;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.Set;

final class ScanContainersInRegionModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final RegionDefinition region;
    private final Set<String> containerTypes;
    private final int maxVolume;

    ScanContainersInRegionModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        this.region = TaskModuleSupport.region(context, step);
        this.containerTypes = TaskModuleSupport.containerTypes(step);
        this.maxVolume = TaskModuleSupport.integerParameter(
                step,
                ConfigValidator.MAX_VOLUME,
                TaskModuleSupport.DEFAULT_SCAN_VOLUME
        );
    }

    @Override
    public TaskModuleResult start() {
        ContainerScanResult scan = context.containerRegions().scan(
                region,
                containerTypes,
                maxVolume
        );
        context.workspace().setContainerScan(region.id(), scan);
        return scan.succeeded()
                ? TaskModuleResult.succeeded(scan.message())
                : TaskModuleResult.failed(scan.message());
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("scan module already completed");
    }

    @Override
    public void cancel() {
    }
}
