package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;

final class OverflowItemsModule {
    private OverflowItemsModule() {
    }

    static TaskModuleExecutor create(TaskExecutionContext context, TaskStep step) {
        var reference = ResourceReferenceSupport.optionalReference(step, TaskModuleIds.REGION_REF,
                ResourceType.REGION);
        String cache = step.parameters().get(ConfigValidator.CACHE_LOCATION_REF);
        if (cache != null) reference = ResourceReference.parse(cache);
        var destination = ResourceReferenceSupport.optionalReference(step, TaskModuleIds.TARGET_REF,
                ResourceType.WAREHOUSE);
        if (ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId())) {
            return context.storageAutomation().robotWarehouseForCache(context, reference,
                    destination);
        }
        var region = reference == null ? null : ResourceReferenceSupport.region(context, reference);
        return context.storageAutomation().overflow(context, region);
    }
}
