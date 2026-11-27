package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryItemBinding;
import dev.stowling.v2.model.CategoryPositionDraft;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CategoryZoneExpansionAxis;
import dev.stowling.v2.model.CategoryZoneExpansionDefinition;
import dev.stowling.v2.model.CategoryZoneOptions;
import dev.stowling.v2.model.CategoryZoneTiler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryOptionsPersistenceTest {
    @TempDir
    Path directory;

    @Test
    void persistsDisabledIndependentDraftsIncludingIncompleteCoordinatesAndMultilineText()
            throws Exception {
        String json = """
                {"scopeMode":"single","ignoreMarkerPositions":false,
                "ignoredMarkerPositions":[{"x":44,"y":null,"z":39}],
                "ignoreRepeatedBlocks":true,"multiItemBindingsEnabled":false,
                "multiItemBindings":[{"name":"Draft","targetType":"container",
                "markerId":"unfinished","position":{"x":3,"y":null,"z":1},
                "itemIds":"minecraft:stone\\nminecraft:dirt\\n","expanded":true}]}
                """;
        CategoryOptionsJson.validate(JsonParser.parseString(json));
        var draft = new Gson().fromJson(json, CategoryZoneOptions.class);
        draft.validateActiveRules();
        var global = new CategoryZoneOptions("single", "marker", "bulk", "inside-box",
                "", false, "");
        var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(0, 64, 0, 0, 64, 0), new AreaBounds(0, 60, 0, 0, 60, 0),
                "DIRECT_BLOCK_ID", List.of(), List.of(), null, null, false, "Group", false, draft);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Zone", List.of(binding),
                null, global);
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(), List.of(), List.of(), List.of(zone));
        Path path = directory.resolve("config.json");
        Files.writeString(path, new Gson().toJson(config));
        var store = new ConfigStore(path);
        var saved = store.read();
        store.save(saved.revision(), saved.config());
        var restored = new ConfigStore(path).read().config().categoryZones().getFirst();
        assertEquals(draft, restored.bindings().getFirst().categoryOptions());
        assertFalse(restored.bindings().getFirst().optionsEnabled());
        assertEquals("minecraft:stone\nminecraft:dirt\n",
                draft.multiItemBindings().getFirst().itemIds());
        assertTrue(draft.multiItemBindings().getFirst().expanded());
    }

    @Test
    void legacyOptionsDefaultToDisabledEmptyRulesAndRejectMalformedNewFields() {
        var legacy = new Gson().fromJson("{}", CategoryZoneOptions.class);
        assertFalse(legacy.ignoreMarkerPositions());
        assertFalse(legacy.ignoreRepeatedBlocks());
        assertFalse(legacy.multiItemBindingsEnabled());
        assertTrue(legacy.multiItemBindings().isEmpty());
        assertTrue(legacy.ignoredMarkerPositions().isEmpty());
        for (String json : List.of(
                "{\"ignoredMarkerPositions\":[{\"x\":1.5,\"y\":2,\"z\":3}]}",
                "{\"ignoredMarkerPositions\":[{\"x\":2147483648,\"y\":2,\"z\":3}]}",
                "{\"ignoreRepeatedBlocks\":\"true\"}",
                "{\"multiItemBindings\":[{\"itemIds\":[\"minecraft:stone\"]}]}",
                "{\"multiItemBindings\":[{\"unknown\":1}]}")) {
            assertThrows(V2ValidationException.class,
                    () -> CategoryOptionsJson.validate(JsonParser.parseString(json)));
        }
    }

    @Test
    void rejectsFixedTargetsOutsideTheirEffectiveAreasButKeepsDisabledDrafts() {
        for (String type : List.of("container", "marker-position")) {
            for (boolean enabled : List.of(false, true)) {
                var rule = new CategoryItemBinding("Outside", type, "",
                        new CategoryPositionDraft(99, 64, 0), "minecraft:dirt", false);
                var options = new CategoryZoneOptions("selection", "marker", "bulk", "inside-box",
                        "", false, "", 27, false, List.of(), false,
                        enabled, List.of(rule));
                var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                        new AreaBounds(0, 64, 0, 0, 64, 0),
                        new AreaBounds(0, 60, 0, 0, 60, 0), "DIRECT_BLOCK_ID");
                var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Zone", List.of(binding),
                        null, options);
                var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                        List.of(), List.of(), List.of(), List.of(zone));
                if (enabled) {
                    assertThrows(V2ValidationException.class,
                            () -> ConfigValidator.requireValid(config));
                } else {
                    ConfigValidator.requireValid(config);
                }
            }
        }
    }

    @Test
    void expansionKeepsAbsoluteRulesAndIndependentScopeAcrossAllGeneratedSlices() {
        var point = new CategoryPositionDraft(2, 64, 0);
        var options = new CategoryZoneOptions("single", "marker", "bulk", "inside-box",
                "", false, "", 27, true, List.of(point), true, false, List.of());
        var template = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(0, 64, 0, 0, 64, 0), new AreaBounds(0, 60, 0, 0, 60, 0),
                "DIRECT_BLOCK_ID", List.of(), List.of(), null, null, false, "Group", true, options);
        var expansion = new CategoryZoneExpansionDefinition(new AreaBounds(0, 60, 0, 3, 64, 0),
                CategoryZoneExpansionAxis.X_POSITIVE, 1, 1);
        var expanded = CategoryZoneTiler.expand(expansion, List.of(template), options);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Zone", expanded,
                expansion, options);
        assertEquals(4, expanded.size());
        for (int index = 0; index < expanded.size(); index++) {
            assertEquals(List.of(point),
                    expanded.get(index).categoryOptions().ignoredMarkerPositions());
            assertEquals(template.id(), CategoryZoneTiler.templateFor(zone, index).id());
        }
    }
}
