package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.AutoClassifyTaskFactory;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryStandingSubregions;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskPriority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryStandingBaselineMigrationTest {
    @TempDir
    Path directory;

    @Test
    void legacyTaskFlagValuesAllUseTheSameBaselineAndAreNotSavedAgain() throws Exception {
        var area = new AreaBounds(0, 64, 0, 0, 64, 0);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(), area, area,
                "DIRECT_BLOCK_ID", List.of(), List.of(), new AreaBounds(2, 65, 7, 2, 65, 7));
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "仓库", List.of(binding));
        var source = new RegionDefinition(UUID.randomUUID(), "来源", 10, 64, 0, 10, 64, 0);
        var task = new AutoClassifyDefinition(UUID.randomUUID(), "分类", false, false, true,
                null, null, "region:" + source.id(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + zone.id())),
                TaskPriority.NORMAL, false, null, null, null, null,
                AutoClassifyDefinition.Kind.CLASSIFICATION, null, null, null, null, null, null);
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                AutoClassifyTaskFactory.tasks(task, List.of()), List.of(source), List.of(zone),
                List.of(), List.of(), List.of(task));
        for (String value : List.of("true", "false", "absent")) {
            var raw = new Gson().toJsonTree(config).getAsJsonObject();
            if (!"absent".equals(value)) raw.getAsJsonArray("autoClassifyTasks").get(0)
                    .getAsJsonObject().addProperty("standingSubregionsEnabled",
                            Boolean.parseBoolean(value));
            Path path = directory.resolve(value + ".json");
            Files.writeString(path, raw.toString());
            var store = new ConfigStore(path);
            var snapshot = store.read();
            var restored = snapshot.config().categoryZones().getFirst();
            assertTrue(restored.bindings().getFirst().standingPlan().enabled());
            assertEquals(65, CategoryStandingSubregions.segments(restored, true)
                    .getFirst().binding().standingArea().firstY());
            store.save(snapshot.revision(), snapshot.config());
            assertFalse(Files.readString(path).contains("standingSubregionsEnabled"));
        }
    }

    @Test
    void oldSupportBlockHeightAndExpandedHorizontalOffsetMigrateOnce() {
        for (boolean independent : List.of(false, true)) {
            JsonObject raw = JsonParser.parseString("""
                    {"categoryOptions":{"specifyStanding":true,
                        "standingPosition":{"x":-8,"y":65,"z":2}},
                    "bindings":[{"id":"expanded","optionsEnabled":false,
                        "categoryOptions":{"specifyStanding":true,
                            "standingPosition":{"x":4,"y":70,"z":5}},
                        "standingArea":{"firstX":-6,"firstY":65,"firstZ":2,
                            "secondX":-6,"secondY":65,"secondZ":2}}]}
                    """).getAsJsonObject();
            var binding = raw.getAsJsonArray("bindings").get(0).getAsJsonObject();
            binding.addProperty("optionsEnabled", independent);
            CategoryStandingMigration.migrate(raw);
            var plan = binding.getAsJsonObject("standingPlan");
            var position = plan.getAsJsonArray("points").get(0).getAsJsonObject()
                    .getAsJsonObject("position");
            assertEquals(-6, position.get("x").getAsInt());
            assertEquals(independent ? 70 : 65, position.get("y").getAsInt());
            assertEquals(independent ? 71 : 66,
                    binding.getAsJsonObject("standingArea").get("firstY").getAsInt());
            assertFalse(raw.getAsJsonObject("categoryOptions").has("specifyStanding"));
            assertFalse(binding.getAsJsonObject("categoryOptions").has("standingPosition"));
            String migrated = raw.toString();
            CategoryStandingMigration.migrate(raw);
            assertEquals(migrated, raw.toString());
        }
    }

    @Test
    void savedMultiStandingPlanTakesPriorityAndDisabledLegacyCoordinatesRemainDrafts() {
        var raw = JsonParser.parseString("""
                {"categoryOptions":{"specifyStanding":false,
                    "standingPosition":{"x":4,"y":66,"z":-5}},
                "bindings":[{"standingPlan":{"enabled":true,"points":[
                    {"standingId":"shared","position":{"x":2,"y":63,"z":7}}]}},{}]}
                """).getAsJsonObject();
        var first = raw.getAsJsonArray("bindings").get(0).getAsJsonObject();
        String original = first.get("standingPlan").toString();
        CategoryStandingMigration.migrate(raw);
        assertEquals(original, first.get("standingPlan").toString());
        var second = raw.getAsJsonArray("bindings").get(1).getAsJsonObject()
                .getAsJsonObject("standingPlan");
        assertFalse(second.get("enabled").getAsBoolean());
        assertEquals(4, second.getAsJsonArray("points").get(0).getAsJsonObject()
                .getAsJsonObject("position").get("x").getAsInt());
    }
}
