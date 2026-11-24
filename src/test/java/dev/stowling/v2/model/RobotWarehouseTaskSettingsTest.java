package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.config.ConfigValidator;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobotWarehouseTaskSettingsTest {
    @TempDir
    Path directory;

    @Test
    void regionDestinationAndPureModeSurviveConfigurationReload() {
        var cache = new RegionDefinition(UUID.randomUUID(), "来源", 0, 64, 0, 1, 64, 1);
        var warehouse = new RegionDefinition(UUID.randomUUID(), "乱序仓库", 8, 64, 0, 9, 64, 1);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(4, 64, 0, 4, 64, 0), new AreaBounds(4, 64, 1, 4, 64, 1),
                "DIRECT_BLOCK_ID");
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "分类目标", List.of(binding));
        var definition = definition("region:" + cache.id(), "region:" + warehouse.id(),
                "category-zone:" + zone.id(), false);
        var tasks = AutoClassifyTaskFactory.tasks(definition, List.of());
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), tasks,
                List.of(cache, warehouse), List.of(zone), List.of(), List.of(), List.of(definition));
        var store = new ConfigStore(directory.resolve("robot-warehouse.json"));
        store.save(store.loadOrReset().revision(), config);
        var reloaded = new ConfigStore(directory.resolve("robot-warehouse.json"))
                .loadOrReset().config();
        var saved = reloaded.autoClassifyTasks().getFirst();
        assertEquals("region:" + warehouse.id(), saved.overflowDestinationRef());
        assertFalse(saved.robotWarehouseMixedBoxes());
        var step = reloaded.tasks().getFirst().steps().stream().filter(value ->
                ConfigValidator.ROBOT_WAREHOUSE.equals(value.moduleId())).findFirst().orElseThrow();
        assertEquals(saved.overflowDestinationRef(),
                step.parameters().get(ConfigValidator.TARGET_REF));
        assertEquals("false", step.parameters().get("mixedBoxes"));
    }

    @Test
    void categoryZonesAndTheSourceCannotBeUsedAsTheRobotWarehouse() {
        String source = "region:" + UUID.randomUUID();
        String zone = "category-zone:" + UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> definition(source, zone, zone, true));
        assertThrows(IllegalArgumentException.class, () -> definition(source, source, zone, true));
    }

    @Test
    void legacyWarehouseReferencesDefaultToMixedBoxes() {
        var legacy = new AutoClassifyDefinition(UUID.randomUUID(), "旧分类", false, false, true,
                null, null, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
                TaskPriority.NORMAL, true, null, null, "warehouse:" + UUID.randomUUID(), null,
                AutoClassifyDefinition.Kind.CLASSIFICATION, null, null, null, false);
        assertTrue(legacy.robotWarehouseMixedBoxes());
    }

    private static AutoClassifyDefinition definition(String source, String destination,
            String zone, boolean mixedBoxes) {
        return new AutoClassifyDefinition(UUID.randomUUID(), "分类", false, false, true,
                null, null, source, List.of(new AutoClassifyAreaDefinition(zone)), TaskPriority.NORMAL,
                true, null, null, destination, null, AutoClassifyDefinition.Kind.CLASSIFICATION,
                null, null, null, false, null, mixedBoxes);
    }
}
