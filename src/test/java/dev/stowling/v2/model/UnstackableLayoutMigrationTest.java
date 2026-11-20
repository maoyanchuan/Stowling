package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.stowling.v2.category.UnstackableCategoryRule;
import dev.stowling.v2.config.ConfigValidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnstackableLayoutMigrationTest {
    @Test
    void movesLegacyTaskDestinationsIntoAReusableLayoutWithoutLosingTheTask() {
        var point = new CoordinatePoint(UUID.randomUUID(), "Drops", 0, 64, 0);
        var cache = new RegionDefinition(UUID.randomUUID(), "Cache", 0, 64, 0, 1, 64, 1);
        var storage = new RegionDefinition(UUID.randomUUID(), "Tools", 10, 64, 0, 12, 65, 1);
        var definition = new AutoClassifyDefinition(UUID.randomUUID(), "Sort", false, true, true,
                "coordinate:" + point.id(), 8, "region:" + cache.id(), List.of(),
                TaskPriority.NORMAL, false, null, "region:" + storage.id());
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point),
                AutoClassifyTaskFactory.tasks(definition, List.of()), List.of(cache, storage),
                List.of(), List.of(), List.of(), List.of(definition));

        AppConfig migrated = TaskFlowNormalizer.normalize(config);
        var updated = migrated.autoClassifyTasks().getFirst();
        var zone = migrated.categoryZones().getFirst();

        assertEquals(definition.id(), updated.id());
        assertNull(updated.unstackableRegionRef());
        assertEquals("category-zone:" + zone.id(),
                updated.classificationAreas().getFirst().categoryZoneRef());
        assertEquals(UnstackableCategoryRule.ID, zone.bindings().getFirst().ruleId());
        assertEquals(new AreaBounds(10, 64, 0, 12, 65, 1),
                zone.bindings().getFirst().storageArea());
        assertEquals(migrated, TaskFlowNormalizer.normalize(migrated));
        assertDoesNotThrow(() -> ConfigValidator.requireValid(migrated));
    }
}
