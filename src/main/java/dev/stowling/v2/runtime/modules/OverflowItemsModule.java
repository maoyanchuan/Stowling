package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;

final class OverflowItemsModule {
    private OverflowItemsModule() {
    }

    static TaskModuleExecutor create(TaskExecutionContext context, TaskStep step) {
        var reference = ResourceReferenceSupport.optionalReference(step, TaskModuleIds.REGION_REF,
                ResourceType.REGION);
        RegionDefinition region = reference == null ? null
                : ResourceReferenceSupport.region(context, reference);
        return ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId())
                ? context.storageAutomation().robotWarehouse(context, region)
                : context.storageAutomation().overflow(context, region);
    }
}
