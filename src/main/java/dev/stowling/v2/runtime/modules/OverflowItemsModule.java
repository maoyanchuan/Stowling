package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.model.ResourceType;
import dev.stowling.v2.model.TaskStep;

final class OverflowItemsModule {
    private OverflowItemsModule() {
    }

    static TaskModuleExecutor create(TaskExecutionContext context, TaskStep step) {
        var reference = ResourceReferenceSupport.optionalReference(step, TaskModuleIds.REGION_REF,
                ResourceType.REGION);
        return context.storageAutomation().overflow(context, reference == null ? null
                : ResourceReferenceSupport.region(context, reference));
    }
}
