package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.AutoClassifyTaskFactory;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskPriority;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    @TempDir
    Path directory;

    @Test
    void reusesValidatedConfigAndDetectsSameLengthExternalEdits() throws Exception {
        Path path = directory.resolve("stowling.json");
        ConfigStore store = new ConfigStore(path);
        var initial = store.loadOrReset();
        var coordinate = new CoordinatePoint(UUID.randomUUID(), "Before", 0, 64, 0);
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(coordinate), List.of());
        var saved = store.save(initial.revision(), config);
        assertSame(saved, store.read());

        Files.writeString(path, Files.readString(path).replace("\"Before\"", "\"Edited\""));
        var edited = store.read();
        assertEquals("Edited", edited.config().coordinates().getFirst().name());
        assertSame(edited, store.read());
        assertThrows(V2RevisionConflictException.class, () -> store.save(saved.revision(), config));
    }

    @Test
    void preservesWholeStorageMappingAfterRestart() throws Exception {
        Path path = directory.resolve("whole-storage.json");
        ConfigStore store = new ConfigStore(path);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(11, -40, 46, 10, -40, 46),
                new AreaBounds(10, -38, 46, 10, -36, 47), "MARKER_CONTAINER_CONTENTS",
                List.of(), List.of(), null, dev.stowling.v2.model.StoragePolicy.DEFAULT, true);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Rotated", List.of(binding));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(), List.of(), List.of(), List.of(zone));

        store.save(store.loadOrReset().revision(), config);

        assertEquals(binding, new ConfigStore(path).loadOrReset().config().categoryZones()
                .getFirst().bindings().getFirst());
    }

    @Test
    void storesExactLooseSlotsAndChecksThePartialBoxDestinationOnRestart() throws Exception {
        AppConfig initial = automaticConfiguration(false);
        var original = initial.autoClassifyTasks().getFirst();
        var originalArea = original.classificationAreas().getFirst();
        var policy = new dev.stowling.v2.model.StoragePolicy(
                dev.stowling.v2.model.StorageMode.MIXED, 35, true, null, true, 19,
                original.detectionCoordinateRef());
        var automatic = new AutoClassifyDefinition(original.id(), original.name(), false,
                true, true, original.detectionCoordinateRef(), 8, original.cacheRegionRef(),
                List.of(new AutoClassifyAreaDefinition(originalArea.categoryZoneRef(),
                        originalArea.ruleId(), originalArea.markerSources(), policy)),
                TaskPriority.NORMAL);
        var config = new AppConfig(initial.schemaVersion(), initial.coordinates(),
                AutoClassifyTaskFactory.tasks(automatic, List.of()), initial.regions(),
                initial.categoryZones(), List.of(), List.of(), List.of(automatic));
        Path path = directory.resolve("partial-boxes.json");
        var store = new ConfigStore(path);
        store.save(store.loadOrReset().revision(), config);
        assertEquals(policy, new ConfigStore(path).loadOrReset().config().autoClassifyTasks()
                .getFirst().classificationAreas().getFirst().storagePolicy());
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        json.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                .getAsJsonArray("classificationAreas").get(0).getAsJsonObject()
                .getAsJsonObject("storagePolicy").addProperty("partialBoxDestinationRef",
                        "coordinate:" + UUID.randomUUID());
        String invalid = json.toString();
        Files.writeString(path, invalid);
        assertThrows(V2ValidationException.class, () -> new ConfigStore(path).loadOrReset());
        assertEquals(invalid, Files.readString(path));
    }

    @Test
    void automaticCleanupSelectionSurvivesRestartAndLegacyTasksDefaultToTheirCache()
            throws Exception {
        AppConfig initial = automaticConfiguration(false);
        var original = initial.autoClassifyTasks().getFirst();
        var policy = new dev.stowling.v2.model.TaskCleanupPolicy(original.detectionCoordinateRef(),
                dev.stowling.v2.model.TaskResumeMode.RESTART);
        var automatic = new AutoClassifyDefinition(original.id(), original.name(), original.enabled(),
                original.collectionEnabled(), original.classificationEnabled(),
                original.detectionCoordinateRef(), original.pickupRadius(), original.cacheRegionRef(),
                original.classificationAreas(), original.collectionPriority(), false, policy);
        AppConfig config = new AppConfig(initial.schemaVersion(), initial.coordinates(),
                AutoClassifyTaskFactory.tasks(automatic, List.of()), initial.regions(),
                initial.categoryZones(), List.of(), List.of(), List.of(automatic));
        Path path = directory.resolve("cleanup-auto.json");
        ConfigStore store = new ConfigStore(path);
        store.save(store.loadOrReset().revision(), config);
        var loaded = new ConfigStore(path).loadOrReset().config();
        assertEquals(policy, loaded.autoClassifyTasks().getFirst().cleanupPolicy());
        assertTrue(loaded.tasks().stream().allMatch(task -> policy.equals(task.cleanupPolicy())));
        var legacy = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        legacy.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject().remove("cleanupPolicy");
        legacy.getAsJsonArray("tasks").forEach(task -> task.getAsJsonObject().remove("cleanupPolicy"));
        Files.writeString(path, legacy.toString());
        var reopened = new ConfigStore(path).loadOrReset().config();
        assertEquals(original.cacheRegionRef(), reopened.autoClassifyTasks().getFirst()
                .cleanupPolicy().destinationRef());
        assertTrue(reopened.tasks().stream().allMatch(task -> original.cacheRegionRef()
                .equals(task.cleanupPolicy().destinationRef())));
    }

    @Test
    void reloadsSavedAutomaticTasksWithOverflowEnabledOrDisabled() throws Exception {
        for (boolean enabled : List.of(false, true)) {
            Path path = directory.resolve("overflow-" + enabled + ".json");
            ConfigStore store = new ConfigStore(path);
            var initial = store.loadOrReset();
            AppConfig config = automaticConfiguration(enabled);
            store.save(initial.revision(), config);
            String content = Files.readString(path);
            assertEquals(config, new ConfigStore(path).loadOrReset().config());
            assertEquals(content, Files.readString(path));
        }
    }

    @Test
    void acceptsAutomaticTasksSavedBeforeOverflowWasAdded() throws Exception {
        Path path = directory.resolve("legacy-auto.json");
        ConfigStore store = new ConfigStore(path);
        store.save(store.loadOrReset().revision(), automaticConfiguration(false));
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        json.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                .remove("overflowEnabled");
        Files.writeString(path, json.toString());
        var loaded = new ConfigStore(path).loadOrReset();
        assertEquals(false, loaded.config().autoClassifyTasks().getFirst().overflowEnabled());
        assertEquals(json.toString(), Files.readString(path));
    }

    @Test
    void stillRejectsUnknownAutomaticTaskFieldsWithoutChangingTheFile() throws Exception {
        Path path = directory.resolve("unknown-auto.json");
        ConfigStore store = new ConfigStore(path);
        store.save(store.loadOrReset().revision(), automaticConfiguration(true));
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        json.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                .addProperty("unknownField", true);
        String content = json.toString();
        Files.writeString(path, content);
        assertThrows(V2ValidationException.class, () -> new ConfigStore(path).loadOrReset());
        assertEquals(content, Files.readString(path));
    }

    private static AppConfig automaticConfiguration(boolean overflowEnabled) {
        var point = new CoordinatePoint(UUID.randomUUID(), "Pickup", 0, 64, 0);
        var region = new RegionDefinition(UUID.randomUUID(), "Cache", 0, 64, 0, 2, 64, 2);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(),
                new AreaBounds(10, 64, 0, 12, 64, 0),
                new AreaBounds(10, 64, 2, 12, 64, 2), "DIRECT_BLOCK_ID");
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Storage", List.of(binding));
        var definition = new AutoClassifyDefinition(UUID.randomUUID(), "Automatic",
                false, true, true, "coordinate:" + point.id(), 8, "region:" + region.id(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + zone.id(),
                        "DIRECT_BLOCK_ID", List.of(CategoryMarkerSource.PLACED_ITEM))),
                TaskPriority.NORMAL, overflowEnabled);
        return new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point),
                AutoClassifyTaskFactory.tasks(definition, List.of()), List.of(region),
                List.of(zone), List.of(), List.of(), List.of(definition));
    }

    @Test
    void rejectsOldSchemaWithoutOverwritingTheFile() throws Exception {
        Path path = directory.resolve("stowling.json");
        String content = "{\"schemaVersion\":6,\"pickupRegions\":[]}";
        Files.writeString(path, content);
        ConfigStore store = new ConfigStore(path);

        assertThrows(V2ValidationException.class, store::loadOrReset);

        assertEquals(content, Files.readString(path));
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void rejectsAStaleRevisionWithoutOverwritingTheCurrentFile() throws Exception {
        Path path = directory.resolve("stowling.json");
        ConfigStore store = new ConfigStore(path);
        V2ConfigSnapshot initial = store.loadOrReset();
        AppConfig changed = new AppConfig(
                AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(new CoordinatePoint(
                        UUID.randomUUID(),
                        "Home",
                        0,
                        64,
                        0
                )),
                List.of()
        );
        V2ConfigSnapshot firstSave = store.save(initial.revision(), changed);
        String savedContent = Files.readString(path);

        assertThrows(
                V2RevisionConflictException.class,
                () -> store.save(initial.revision(), AppConfig.empty())
        );
        assertEquals(savedContent, Files.readString(path));
        assertTrue(!firstSave.revision().equals(initial.revision()));
    }

    @Test
    void keepsACurrentSchemaSevenConfiguration() throws Exception {
        Path path = directory.resolve("stowling.json");
        ConfigStore store = new ConfigStore(path);
        V2ConfigSnapshot initial = store.loadOrReset();
        String current = Files.readString(path);

        V2ConfigSnapshot loaded = store.loadOrReset();

        assertEquals(initial.revision(), loaded.revision());
        assertEquals(current, Files.readString(path));
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void keepsCoordinatesThatPredateOptionalFields() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 7,
                    "coordinates": [{
                        "id": "00000000-0000-0000-0000-000000000001",
                        "name": "Home",
                        "x": 0,
                        "y": 64,
                        "z": 0
                    }],
                    "tasks": []
                }
                """);
        ConfigStore store = new ConfigStore(path);

        CoordinatePoint point = store.loadOrReset().config().coordinates().getFirst();

        assertEquals(null, point.dimension());
        assertEquals(null, point.radius());
        assertTrue(store.loadOrReset().config().categoryZones().isEmpty());
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void keepsTasksThatPredatePriorityAndRepeatFields() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 7,
                    "coordinates": [{
                        "id": "00000000-0000-0000-0000-000000000001",
                        "name": "Home",
                        "x": 0,
                        "y": 64,
                        "z": 0
                    }],
                    "tasks": [{
                        "id": "00000000-0000-0000-0000-000000000002",
                        "name": "Travel",
                        "steps": [{
                            "id": "00000000-0000-0000-0000-000000000003",
                            "moduleId": "MOVE_TO_COORDINATE",
                            "parameters": {
                                "coordinateId":
                                    "00000000-0000-0000-0000-000000000001"
                            }
                        }]
                    }]
                }
                """);
        ConfigStore store = new ConfigStore(path);

        var task = store.loadOrReset().config().tasks().getFirst();

        assertEquals(dev.stowling.v2.model.TaskPriority.NORMAL, task.priority());
        assertEquals(false, task.repeatWhenIdle());
        assertEquals(null, task.folderId());
        assertTrue(store.loadOrReset().config().taskFolders().isEmpty());
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void expandsLegacyClassificationWithExplicitZone()
            throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 7,
                    "coordinates": [],
                    "tasks": [{
                        "id": "00000000-0000-0000-0000-000000000010",
                        "name": "Legacy sort",
                        "steps": [{
                            "id": "00000000-0000-0000-0000-000000000011",
                            "moduleId": "SORT_CACHE_ITEMS",
                            "parameters": {
                                "cacheRegionId":
                                    "00000000-0000-0000-0000-000000000012",
                                "categoryZoneId":
                                    "00000000-0000-0000-0000-000000000013",
                                "scanSource": "previous-scan"
                            }
                        }]
                    }],
                    "regions": [{
                        "id": "00000000-0000-0000-0000-000000000012",
                        "name": "Cache",
                        "firstX": 0,
                        "firstY": 64,
                        "firstZ": 0,
                        "secondX": 8,
                        "secondY": 70,
                        "secondZ": 8
                    }],
                    "categoryZones": [{
                        "id": "00000000-0000-0000-0000-000000000013",
                        "name": "Zone",
                        "bindings": [{
                            "id": "00000000-0000-0000-0000-000000000014",
                            "markerArea": {
                                "firstX": 20, "firstY": 64, "firstZ": 20,
                                "secondX": 24, "secondY": 64, "secondZ": 24
                            },
                            "storageArea": {
                                "firstX": 20, "firstY": 64, "firstZ": 18,
                                "secondX": 24, "secondY": 64, "secondZ": 18
                            },
                            "ruleId": "DIRECT_BLOCK_ID"
                        }]
                    }]
                }
                """);
        ConfigStore store = new ConfigStore(path);

        V2ConfigSnapshot snapshot = store.loadOrReset();

        assertEquals(
                AppConfig.CURRENT_SCHEMA_VERSION,
                snapshot.config().schemaVersion()
        );
        assertEquals(1, snapshot.config().regions().size());
        assertEquals(2, snapshot.config().tasks().size());
        assertEquals(
                dev.stowling.v2.model.TaskInterruptionMode.CUSTOM,
                snapshot.config().tasks().getFirst().interruptionMode()
        );
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void storesIndependentTaskTriggerConfiguration() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 7,
                    "coordinates": [{
                        "id": "00000000-0000-0000-0000-000000000001",
                        "name": "Drops",
                        "x": 0,
                        "y": 64,
                        "z": 0,
                        "radius": 8
                    }],
                    "tasks": [{
                        "id": "00000000-0000-0000-0000-000000000002",
                        "name": "Pickup",
                        "priority": "BACKGROUND",
                        "repeatWhenIdle": false,
                        "steps": [{
                            "id": "00000000-0000-0000-0000-000000000003",
                            "moduleId": "COLLECT_DROPPED_ITEMS",
                            "parameters": {
                                "coordinateId":
                                    "00000000-0000-0000-0000-000000000001"
                            }
                        }]
                    }],
                    "triggers": [{
                        "id": "00000000-0000-0000-0000-000000000004",
                        "name": "Pickup drops",
                        "type": "DROPPED_ITEMS",
                        "targetTaskId":
                            "00000000-0000-0000-0000-000000000002",
                        "priority": "NORMAL",
                        "intervalTicks": 20,
                        "cooldownTicks": 100,
                        "coordinateId":
                            "00000000-0000-0000-0000-000000000001",
                        "itemFilter": "minecraft:oak_log"
                    }]
                }
                """);
        ConfigStore store = new ConfigStore(path);

        var trigger = store.loadOrReset().config().triggers().getFirst();

        assertEquals(
                "minecraft:oak_log",
                trigger.itemFilter()
        );
        assertEquals(20, trigger.intervalTicks());
        assertEquals("Pickup drops", trigger.name());
    }

    @Test
    void keepsCategoryBindingsThatPredateIgnoredSupportBlocks() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 7,
                    "coordinates": [],
                    "tasks": [],
                    "categoryZones": [{
                        "id": "00000000-0000-0000-0000-000000000002",
                        "name": "A",
                        "bindings": [{
                            "id": "00000000-0000-0000-0000-000000000003",
                            "markerArea": {
                                "firstX": 7, "firstY": -34, "firstZ": 7,
                                "secondX": 18, "secondY": -34, "secondZ": 7
                            },
                            "storageArea": {
                                "firstX": 7, "firstY": -34, "firstZ": 5,
                                "secondX": 18, "secondY": -34, "secondZ": 6
                            },
                            "ruleId": "DIRECT_BLOCK_ID"
                        }]
                    }]
                }
                """);
        ConfigStore store = new ConfigStore(path);

        assertTrue(store.loadOrReset().config().categoryZones()
                .getFirst()
                .bindings()
                .getFirst()
                .ignoredMarkerBlockIds()
                .isEmpty());
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void upgradesSchemaTwelveCategoryZonesAsManualBindings() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 12,
                    "coordinates": [],
                    "tasks": [],
                    "categoryZones": [{
                        "id": "00000000-0000-0000-0000-000000000002",
                        "name": "Existing",
                        "bindings": [{
                            "id": "00000000-0000-0000-0000-000000000003",
                            "markerArea": {
                                "firstX": 7, "firstY": 64, "firstZ": 7,
                                "secondX": 18, "secondY": 64, "secondZ": 7
                            },
                            "storageArea": {
                                "firstX": 7, "firstY": 64, "firstZ": 5,
                                "secondX": 18, "secondY": 64, "secondZ": 6
                            },
                            "ruleId": "DIRECT_BLOCK_ID"
                        }]
                    }]
                }
                """);
        ConfigStore store = new ConfigStore(path);

        var snapshot = store.loadOrReset();

        assertEquals(
                AppConfig.CURRENT_SCHEMA_VERSION,
                snapshot.config().schemaVersion()
        );
        assertEquals(null, snapshot.config().categoryZones()
                .getFirst().expansion());
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void upgradesSchemaThirteenSingleTemplateExpansion() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 13,
                    "coordinates": [],
                    "tasks": [],
                    "categoryZones": [{
                        "id": "00000000-0000-0000-0000-000000000002",
                        "name": "Existing auto",
                        "bindings": [{
                            "id": "00000000-0000-0000-0000-000000000003",
                            "markerArea": {
                                "firstX": 0, "firstY": 64, "firstZ": 20,
                                "secondX": 4, "secondY": 64, "secondZ": 20
                            },
                            "storageArea": {
                                "firstX": 0, "firstY": 64, "firstZ": 18,
                                "secondX": 4, "secondY": 64, "secondZ": 18
                            },
                            "ruleId": "DIRECT_BLOCK_ID"
                        }],
                        "expansion": {
                            "area": {
                                "firstX": 0, "firstY": 63, "firstZ": 17,
                                "secondX": 4, "secondY": 65, "secondZ": 21
                            },
                            "direction": "X_POSITIVE",
                            "step": 5
                        }
                    }]
                }
                """);
        ConfigStore store = new ConfigStore(path);

        var snapshot = store.loadOrReset();

        assertEquals(
                AppConfig.CURRENT_SCHEMA_VERSION,
                snapshot.config().schemaVersion()
        );
        assertEquals(1, snapshot.config().categoryZones()
                .getFirst().expansion().templateCount());
    }

    @Test
    void rejectsInvalidCurrentSchemaWithoutOverwritingTheFile() throws Exception {
        Path path = directory.resolve("stowling.json");
        String content = """
                {
                    "schemaVersion": 7,
                    "coordinates": [],
                    "tasks": [],
                    "radius": 8
                }
                """;
        Files.writeString(path, content);
        ConfigStore store = new ConfigStore(path);

        assertThrows(V2ValidationException.class, store::loadOrReset);

        assertEquals(content, Files.readString(path));
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void preservesCoordinatesWhenLegacySortHasNoCategoryZone() throws Exception {
        Path path = directory.resolve("stowling.json");
        String content = """
                {
                    "schemaVersion": 7,
                    "coordinates": [{
                        "id": "00000000-0000-0000-0000-000000000001",
                        "name": "Home",
                        "x": 0,
                        "y": 64,
                        "z": 0
                    }],
                    "tasks": [{
                        "id": "00000000-0000-0000-0000-000000000002",
                        "name": "Legacy sort",
                        "steps": [{
                            "id": "00000000-0000-0000-0000-000000000003",
                            "moduleId": "SORT_CACHE_ITEMS",
                            "parameters": {
                                "cacheRegionId":
                                    "00000000-0000-0000-0000-000000000004"
                            }
                        }]
                    }]
                }
                """;
        Files.writeString(path, content);
        ConfigStore store = new ConfigStore(path);

        assertThrows(V2ValidationException.class, store::loadOrReset);
        assertEquals(content, Files.readString(path));
        assertTrue(content.contains("\"name\": \"Home\""));
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }
}
