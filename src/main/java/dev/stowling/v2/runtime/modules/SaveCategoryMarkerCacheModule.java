package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;
import java.util.UUID;

final class SaveCategoryMarkerCacheModule implements TaskModuleExecutor {
    private final TaskExecutionContext context;
    private final UUID zoneId;

    SaveCategoryMarkerCacheModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        this.context = context;
        ResourceReference reference = ResourceReferenceSupport.reference(
                step,
                TaskModuleIds.CATEGORY_ZONE_REF,
                ResourceType.CATEGORY_ZONE,
                TaskModuleIds.CATEGORY_ZONE_ID
        );
        CategoryZoneDefinition zone = ResourceReferenceSupport.categoryZone(
                context,
                reference
        );
        this.zoneId = zone.id();
    }

    @Override
    public TaskModuleResult start() {
        UUID plannedZone = context.workspace().markerZoneId().orElse(null);
        if (plannedZone == null) {
            return TaskModuleResult.failed("缺少分类标记扫描计划");
        }
        if (!plannedZone.equals(zoneId)) {
            return TaskModuleResult.failed(
                    "当前分类标记计划不属于所配置的分类区"
            );
        }
        if (context.workspace().markerTargetCount() > 0
                && !context.workspace().allMarkerContainersRead()) {
            return TaskModuleResult.failed(
                    "分类标记容器尚未全部读取: "
                            + context.workspace().markerReadCount()
                            + "/"
                            + context.workspace().markerTargetCount()
            );
        }
        if (context.workspace().markerTargetCount() > 0
                || !context.workspace().markerCache().isEmpty()) {
            context.categoryMarkerCache().replaceBindings(
                    context.workspace().markerCache()
            );
        }
        return TaskModuleResult.succeeded("分类标记缓存已保存");
    }

    @Override
    public TaskModuleResult tick() {
        throw new IllegalStateException("marker cache already saved");
    }

    @Override
    public void cancel() {
    }
}
