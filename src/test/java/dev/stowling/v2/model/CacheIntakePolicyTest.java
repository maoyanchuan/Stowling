package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CacheIntakePolicyTest {
    @Test
    void oldDefinitionsDisableExperimentalPackingAndEnabledPoliciesRoundTrip() {
        var definition = new AutoClassifyDefinition(UUID.randomUUID(), "Cache", false, true, true,
                "coordinate:" + UUID.randomUUID(), 8, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
                TaskPriority.NORMAL);
        assertEquals(CacheIntakePolicy.DEFAULT, definition.cachePolicy());
        var gson = new Gson();
        var json = gson.toJsonTree(definition).getAsJsonObject();
        json.remove("cachePolicy");
        assertEquals(CacheIntakePolicy.DEFAULT,
                gson.fromJson(json, AutoClassifyDefinition.class).cachePolicy());

        json.add("cachePolicy", com.google.gson.JsonParser.parseString(
                "{\"mode\":\"CLASSIFY_FIRST\",\"thresholdPercent\":65}"));
        assertFalse(gson.fromJson(json, AutoClassifyDefinition.class).cachePolicy().enabled());

        var policy = new CacheIntakePolicy(true, CacheIntakePolicy.Mode.CLASSIFY_FIRST, 65);
        json.add("cachePolicy", gson.toJsonTree(policy));
        var loaded = gson.fromJson(json, AutoClassifyDefinition.class);
        assertEquals(policy, loaded.cachePolicy());
        var intake = AutoClassifyTaskFactory.tasks(loaded, List.of()).getFirst()
                .steps().get(1).steps().get(1);
        assertEquals("CLASSIFY_FIRST", intake.parameters().get("cacheIntakeMode"));
        assertEquals("65", intake.parameters().get("cacheThresholdPercent"));
    }

    @Test
    void disablingPackingReplacesPersistedExperimentalStepsAndRemainsDisabledOnRestart() {
        var original = new AutoClassifyDefinition(UUID.randomUUID(), "Cache", false, true, true,
                "coordinate:" + UUID.randomUUID(), 8, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
                TaskPriority.NORMAL);
        var gson = new Gson();
        var json = gson.toJsonTree(original).getAsJsonObject();
        json.add("cachePolicy", gson.toJsonTree(new CacheIntakePolicy(
                true, CacheIntakePolicy.Mode.PACK_FIRST, 65)));
        var enabled = gson.fromJson(json, AutoClassifyDefinition.class);
        var tasks = AutoClassifyTaskFactory.tasks(enabled, List.of());
        json.getAsJsonObject("cachePolicy").remove("enabled");
        var legacy = gson.fromJson(json, AutoClassifyDefinition.class);
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(), tasks,
                List.of(), List.of(), List.of(), List.of(), List.of(legacy));

        var migrated = TaskFlowNormalizer.normalize(config);
        TaskStep ordinary = migrated.tasks().getFirst().steps().get(1).steps().get(1);
        assertEquals(TaskStepKind.REPEAT, ordinary.kind());
        assertEquals("DEPOSIT_ITEMS_TO_CONTAINERS", ordinary.steps().getLast().moduleId());
        assertEquals(65, migrated.autoClassifyTasks().getFirst().cachePolicy().thresholdPercent());
        assertFalse(migrated.autoClassifyTasks().getFirst().cachePolicy().enabled());
        assertEquals(migrated, TaskFlowNormalizer.normalize(migrated));

        var reenabled = new AppConfig(config.schemaVersion(), List.of(), migrated.tasks(),
                List.of(), List.of(), List.of(), List.of(), List.of(enabled));
        assertEquals("MANAGE_CACHE_INTAKE", TaskFlowNormalizer.normalize(reenabled)
                .tasks().getFirst().steps().get(1).steps().get(1).moduleId());
    }

    @Test
    void rejectsPercentagesOutsideTheConfiguredRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new CacheIntakePolicy(CacheIntakePolicy.Mode.PACK_FIRST, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CacheIntakePolicy(CacheIntakePolicy.Mode.PACK_FIRST, 101));
    }
}
