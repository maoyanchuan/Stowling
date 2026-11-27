package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryStandingPlan;
import dev.stowling.v2.model.CategoryZoneDefinition;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryStandingPersistenceTest {
    @TempDir
    Path directory;

    @Test
    void disabledDraftSurvivesConfigurationReadSaveAndReloadOutsideBusinessOptions()
            throws Exception {
        var json = JsonParser.parseString("""
                {"enabled":false,"points":[{"position":{"x":2,"y":null,"z":7},
                "subregionsEnabled":false,"expanded":true,"standingId":"shared-standing",
                "markerArea":{"firstX":0,"firstY":64,"firstZ":0,
                "secondX":0,"secondY":64,"secondZ":4},
                "storageArea":{"firstX":1,"firstY":64,"firstZ":0,
                "secondX":1,"secondY":64,"secondZ":4}}]}
                """);
        CategoryStandingJson.validate(json);
        var plan = new Gson().fromJson(json, CategoryStandingPlan.class);
        var bounds = new AreaBounds(0, 64, 0, 1, 64, 9);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(), bounds, bounds,
                "DIRECT_BLOCK_ID", List.of(), List.of(), null, null, false, "组", false,
                null, plan);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "仓库", List.of(binding));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(), List.of(), List.of(), List.of(zone));
        Path path = directory.resolve("config.json");
        Files.writeString(path, new Gson().toJson(config));
        var store = new ConfigStore(path);
        var loaded = store.read();
        store.save(loaded.revision(), loaded.config());
        var restored = new ConfigStore(path).read().config().categoryZones()
                .getFirst().bindings().getFirst();
        assertEquals(plan, restored.standingPlan());
        assertEquals("shared-standing", restored.standingPlan().points().getFirst().standingId());
        assertFalse(restored.optionsEnabled());
    }

    @Test
    void requestAndConfigurationValidationRejectMalformedFields() {
        for (String json : List.of("{\"enabled\":\"true\",\"points\":[]}",
                "{\"points\":[{\"position\":{\"x\":1.5}}]}",
                "{\"points\":[{\"position\":{\"y\":2147483648}}]}",
                "{\"points\":[{\"storageArea\":{\"firstX\":0}}]}",
                "{\"points\":[{\"unknown\":true}]}",
                "{\"points\":[{\"standingId\":12}]}")) {
            assertThrows(V2ValidationException.class,
                    () -> CategoryStandingJson.validate(JsonParser.parseString(json)));
        }
    }
}
