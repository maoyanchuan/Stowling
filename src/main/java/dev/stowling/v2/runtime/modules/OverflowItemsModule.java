package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;

final class OverflowItemsModule {
    private OverflowItemsModule() {
    }

    static TaskModuleExecutor create(TaskExecutionContext context, TaskStep step) {
        if (dev.stowling.v2.model.ClassificationRemainderPolicy.MODULE.equals(step.moduleId())) {
            return context.storageAutomation().routeClassificationRemainders(context,
                    ResourceReference.parse(step.parameters().get(
                            ConfigValidator.CACHE_LOCATION_REF)),
                    ResourceReference.parse(step.parameters().get(ConfigValidator.TARGET_REF)),
                    step.parameters().get(dev.stowling.v2.model.ClassificationRemainderPolicy.KIND));
        }
        var reference = ResourceReferenceSupport.optionalReference(step, TaskModuleIds.REGION_REF,
                ResourceType.REGION);
        String cache = step.parameters().get(ConfigValidator.CACHE_LOCATION_REF);
        if (cache != null) reference = ResourceReference.parse(cache);
        var destination = ResourceReferenceSupport.optionalReference(step, TaskModuleIds.TARGET_REF,
                ResourceType.WAREHOUSE);
        if (ConfigValidator.ROBOT_WAREHOUSE.equals(step.moduleId())) {
            return context.storageAutomation().robotWarehouseForCache(context, reference,
                    destination, TaskModuleSupport.booleanParameter(step, "mixedBoxes", true));
        }
        var region = reference == null ? null : ResourceReferenceSupport.region(context, reference);
        return context.storageAutomation().overflow(context, region);
    }
}
