package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.v2.config.ConfigValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PickupCacheTaskTest {
    @Test
    void ordinaryPickupUsesOneCycleWithARealCacheReferenceAndValidConfiguration() {
        var point = new CoordinatePoint(UUID.randomUUID(), "Pickup", 0, 64, 0);
        var cache = new CoordinatePoint(UUID.randomUUID(), "Cache", 1, 64, 0);
        var definition = definition(point, cache);
        var tasks = AutoClassifyTaskFactory.tasks(definition, List.of());

        assertEquals(1, tasks.getFirst().steps().size());
        var cycle = tasks.getFirst().steps().getFirst();
        assertEquals(ConfigValidator.COLLECT_DROPPED_ITEMS, cycle.moduleId());
        assertEquals("coordinate:" + cache.id(), cycle.parameters()
                .get(ConfigValidator.CACHE_LOCATION_REF));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point, cache), tasks,
                List.of(), List.of(), List.of(), List.of(), List.of(definition));
        ConfigValidator.requireValid(config);
        assertEquals(config, TaskFlowNormalizer.normalize(config));
    }

    @Test
    void savedPickupFlowsMigrateWithoutLosingIdentityOrTheirPostSuccessTask() {
        var point = new CoordinatePoint(UUID.randomUUID(), "Pickup", 0, 64, 0);
        var cache = new CoordinatePoint(UUID.randomUUID(), "Cache", 1, 64, 0);
        var definition = definition(point, cache);
        var tasks = new ArrayList<>(AutoClassifyTaskFactory.tasks(definition, List.of()));
        TaskDefinition generated = tasks.getFirst();
        var legacy = new ArrayList<>(TaskFlowTemplates.collect("coordinate:" + point.id(),
                "coordinate:" + cache.id(), Map.of()));
        TaskStep followUp = TaskStep.createTask(UUID.randomUUID(), Map.of(
                ConfigValidator.TASK_REF, generated.interruptionTaskRef(),
                ConfigValidator.PRIORITY, TaskPriority.NORMAL.name(),
                ConfigValidator.CREATE_AFTER_SUCCESS, "true",
                ConfigValidator.CREATE_DELAY_SECONDS, "15"));
        legacy.add(followUp);
        tasks.set(0, new TaskDefinition(generated.id(), generated.name(), generated.priority(),
                false, legacy, generated.interruptionMode(), generated.interruptionTaskRef(),
                false, false, null, generated.cleanupPolicy()));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point, cache), tasks,
                List.of(), List.of(), List.of(), List.of(), List.of(definition));

        AppConfig migrated = TaskFlowNormalizer.normalize(config);
        TaskDefinition pickup = migrated.tasks().getFirst();

        assertEquals(generated.id(), pickup.id());
        assertEquals(legacy.getFirst().id(), pickup.steps().getFirst().id());
        assertEquals(2, pickup.steps().size());
        assertEquals(followUp, pickup.steps().getLast());
        assertEquals(generated.interruptionTaskRef(), pickup.interruptionTaskRef());
        ConfigValidator.requireValid(migrated);
        assertEquals(migrated, TaskFlowNormalizer.normalize(migrated));
    }

    private static AutoClassifyDefinition definition(CoordinatePoint point, CoordinatePoint cache) {
        return new AutoClassifyDefinition(UUID.randomUUID(), "Pickup", true, true, false,
                "coordinate:" + point.id(), 8, "coordinate:" + cache.id(), List.of(),
                TaskPriority.NORMAL, false, null, null, null, null,
                AutoClassifyDefinition.Kind.PICKUP, null, 1, 0);
    }
}
