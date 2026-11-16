package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.UUID;

final class RefreshCategoryMarkerCacheModule
        implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final CategoryZoneDefinition zone;
    private final int maxVolume;
    private final MarkerContainerScanAction.Plan plan;
    private MarkerContainerInspectionAction inspection;
    private boolean saved;

    RefreshCategoryMarkerCacheModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        UUID zoneId = UUID.fromString(
                step.parameters().get(ConfigValidator.CATEGORY_ZONE_ID)
        );
        this.zone = context.categoryZones().get(zoneId);
        if (zone == null) {
            throw new IllegalArgumentException(
                    "category zone does not exist: " + zoneId
            );
        }
        this.maxVolume = TaskModuleSupport.integerParameter(
                step,
                ConfigValidator.MAX_VOLUME,
                TaskModuleSupport.DEFAULT_SCAN_VOLUME
        );
        this.plan = MarkerContainerScanAction.scan(
                context,
                zone,
                maxVolume
        );
    }

    @Override
    public TaskModuleResult start() {
        if (!plan.error().isEmpty()) {
            return TaskModuleResult.failed(plan.error());
        }
        inspection = new MarkerContainerInspectionAction(context, plan);
        return inspection.start();
    }

    @Override
    public TaskModuleResult tick() {
        if (inspection == null) {
            return TaskModuleResult.failed("分类标记容器扫描尚未开始");
        }
        TaskModuleResult result = inspection.tick();
        if (result.state() != TaskModuleState.SUCCEEDED || saved) {
            return result;
        }
        MarkerCacheSaveAction.save(context, inspection.cache());
        saved = true;
        return result;
    }

    @Override
    public void cancel() {
        if (inspection != null) {
            inspection.cancel();
        }
    }
}
