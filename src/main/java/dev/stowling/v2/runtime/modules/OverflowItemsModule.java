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
        var destination = ResourceReferenceSupport.optionalReference(step, TaskModuleIds.TARGET_REF,
                ResourceType.COORDINATE);
        return ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId())
                ? context.storageAutomation().robotWarehouse(context, region, destination)
                : context.storageAutomation().overflow(context, region);
    }
}
