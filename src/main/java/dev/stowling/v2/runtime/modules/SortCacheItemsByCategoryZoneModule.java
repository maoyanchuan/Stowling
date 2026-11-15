package dev.stowling.v2.runtime.modules;

import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.TaskStep;
import java.util.UUID;

final class SortCacheItemsByCategoryZoneModule
        implements TaskModuleExecutor {
    private final SortCacheItemsModule delegate;

    SortCacheItemsByCategoryZoneModule(
            TaskExecutionContext context,
            TaskStep step
    ) {
        UUID zoneId = UUID.fromString(
                step.parameters().get(ConfigValidator.CATEGORY_ZONE_ID)
        );
        CategoryZoneDefinition zone = context.categoryZones().get(zoneId);
        if (zone == null) {
            throw new IllegalArgumentException(
                    "category zone does not exist: " + zoneId
            );
        }
        this.delegate = new SortCacheItemsModule(context, step, zone);
    }

    @Override
    public TaskModuleResult start() {
        return delegate.start();
    }

    @Override
    public TaskModuleResult tick() {
        return delegate.tick();
    }

    @Override
    public void cancel() {
        delegate.cancel();
    }
}
