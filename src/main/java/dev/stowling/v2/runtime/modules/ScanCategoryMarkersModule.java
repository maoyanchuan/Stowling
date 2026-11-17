package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.Set;

final class ScanCategoryMarkersModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final CategoryZoneDefinition zone;
    private final int maxVolume;
    private final Set<CategoryMarkerSource> markerSources;

    ScanCategoryMarkersModule(TaskExecutionContext context, TaskStep step) {
        this.context = context;
        ResourceReference reference = ResourceReferenceSupport.reference(
                step,
                TaskModuleIds.CATEGORY_ZONE_REF,
                ResourceType.CATEGORY_ZONE,
                TaskModuleIds.CATEGORY_ZONE_ID
        );
        CategoryZoneDefinition configuredZone =
                ResourceReferenceSupport.categoryZone(context, reference);
        this.zone = TaskModuleSupport.categoryZoneSelection(
                step,
                configuredZone
        );
        this.maxVolume = TaskModuleSupport.integerParameter(
                step,
                TaskModuleIds.MAX_VOLUME,
                TaskModuleSupport.DEFAULT_SCAN_VOLUME
        );
        this.markerSources = TaskModuleSupport.optionalMarkerSources(step);
    }

    @Override
    public TaskModuleResult start() {
        MarkerContainerScanAction.Plan plan = MarkerContainerScanAction.scan(
                context,
                zone,
                maxVolume,
                markerSources
        );
        if (!plan.error().isEmpty()) {
            return TaskModuleResult.failed(plan.error());
        }
        context.workspace().setMarkerPlan(zone.id(), plan);
        return TaskModuleResult.succeeded(
                "已扫描 " + plan.targets().size() + " 个分类标记容器"
        );
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("marker scan already completed");
    }

    @Override
    public void cancel() {
    }
}
