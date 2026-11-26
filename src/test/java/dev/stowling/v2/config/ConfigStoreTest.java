package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.AreaBounds;
import dev.stowling.v2.model.AutoClassifyAreaDefinition;
import dev.stowling.v2.model.AutoClassifyDefinition;
import dev.stowling.v2.model.AutoClassifyTaskFactory;
import dev.stowling.v2.model.CategoryBindingDefinition;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CategoryZoneExpansionAxis;
import dev.stowling.v2.model.CategoryZoneExpansionDefinition;
import dev.stowling.v2.model.CategoryZoneOptions;
import dev.stowling.v2.model.CategoryZoneTiler;
import dev.stowling.v2.model.ClassificationRemainderPolicy;
import dev.stowling.v2.model.ContainerCacheRefresh;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.StoragePolicy;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStep;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    @TempDir
    Path directory;

    @Test
    void overflowPackingSwitchSurvivesDiskSaveAndRestartWithTotalOverflowOnOrOff() {
        for (boolean total : List.of(false, true)) {
            for (boolean pack : List.of(false, true)) {
                var config = overflowPackingConfiguration(total, pack);
                var store = new ConfigStore(directory.resolve("packing-" + total + pack + ".json"));
                var saved = store.save(store.loadOrReset().revision(), config);
                var reopened = new ConfigStore(directory.resolve("packing-" + total + pack + ".json"))
                        .read();
                assertEquals(saved, reopened);
                var definition = reopened.config().autoClassifyTasks().getFirst();
                assertEquals(total, definition.remainderPolicy().overflowEnabled());
                assertEquals(pack, definition.remainderPolicy().overflowPackingEnabled());
                assertTrue(definition.overflowEnabled());
                var snapshot = dev.stowling.v2.model.TaskCleanupRouting.fromSteps(
                        reopened.config().tasks().getFirst().steps());
                assertEquals(total && pack, snapshot.overflowPackingEnabled());
            }
        }
    }

    @Test
    void missingOverflowPackingFieldDefaultsOffWithoutRewritingLegacyFile() throws Exception {
        var json = new Gson().toJsonTree(overflowPackingConfiguration(true, false))
                .getAsJsonObject();
        json.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                .getAsJsonObject("remainderPolicy").remove("overflowPackingEnabled");
        var path = directory.resolve("packing-legacy.json");
        Files.writeString(path, json.toString());
        var loaded = new ConfigStore(path).read();
        assertFalse(loaded.config().autoClassifyTasks().getFirst()
                .remainderPolicy().overflowPackingEnabled());
        assertEquals(json.toString(), Files.readString(path));
    }

    @Test
    void overflowPackingRejectsNonBooleansAndUnknownFieldsWithoutChangingTheFile() throws Exception {
        for (String value : List.of("\"true\"", "1", "null")) {
            var json = new Gson().toJsonTree(overflowPackingConfiguration(true, false))
                    .getAsJsonObject();
            json.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                    .getAsJsonObject("remainderPolicy")
                    .add("overflowPackingEnabled", JsonParser.parseString(value));
            var path = directory.resolve("packing-invalid.json");
            Files.writeString(path, json.toString());
            var error = assertThrows(V2ValidationException.class, () -> new ConfigStore(path).read());
            assertTrue(error.getMessage().contains("overflowPackingEnabled 必须是布尔值"));
            assertEquals(json.toString(), Files.readString(path));
        }
        var json = new Gson().toJsonTree(overflowPackingConfiguration(true, true)).getAsJsonObject();
        json.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                .getAsJsonObject("remainderPolicy").addProperty("unknownPackingField", true);
        var path = directory.resolve("packing-unknown.json");
        Files.writeString(path, json.toString());
        var error = assertThrows(V2ValidationException.class, () -> new ConfigStore(path).read());
        assertTrue(error.getMessage().contains("unknownPackingField"));
        assertEquals(json.toString(), Files.readString(path));
    }

    private static AppConfig overflowPackingConfiguration(boolean total, boolean pack) {
        var initial = automaticConfiguration(true);
        var original = initial.autoClassifyTasks().getFirst();
        var policy = new ClassificationRemainderPolicy(false, null, total,
                "coordinate:" + initial.coordinates().getFirst().id(), pack);
        var definition = new AutoClassifyDefinition(original.id(), original.name(), false,
                false, true, null, null, original.cacheLocationRef(),
                original.classificationAreas(), original.collectionPriority(), true,
                original.cleanupPolicy(), null, original.overflowDestinationRef(), null,
                AutoClassifyDefinition.Kind.CLASSIFICATION, null, null, null, false,
                policy, original.robotWarehouseMixedBoxes());
        return new AppConfig(initial.schemaVersion(), initial.coordinates(),
                AutoClassifyTaskFactory.tasks(definition, List.of()), initial.regions(),
                initial.categoryZones(), List.of(), List.of(), List.of(definition),
                initial.coordinateCatalog());
    }

    @Test
    void refreshMultiAreaOrderAndIndependentPauseSwitchRoundTrip() throws Exception {
        var first = new RegionDefinition(UUID.randomUUID(), "First", 0, 64, 0, 1, 64, 0);
        var second = new RegionDefinition(UUID.randomUUID(), "Second", 8, 64, 0, 9, 64, 0);
        for (boolean multi : List.of(false, true)) {
            for (boolean food : List.of(false, true)) {
                var parameters = Map.of("targetRefs", new Gson().toJson(List.of(
                        "region:" + second.id(), "region:" + first.id())),
                        "multiAreaEnabled", Boolean.toString(multi),
                        "foodPauseResumeEnabled", Boolean.toString(food));
                var task = new TaskDefinition(UUID.randomUUID(), "Refresh", List.of(
                        TaskStep.action(UUID.randomUUID(), ContainerCacheRefresh.MODULE,
                                parameters)));
                var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                        List.of(task), List.of(first, second), List.of());
                Path path = directory.resolve("refresh-order-" + multi + "-" + food + ".json");
                Files.writeString(path, new Gson().toJson(config));
                var store = new ConfigStore(path);
                var restored = store.read();
                assertEquals(parameters, restored.config().tasks().getFirst()
                        .steps().getFirst().parameters());
                store.save(restored.revision(), restored.config());
                assertEquals(restored.config(), new ConfigStore(path).read().config());
            }
        }
    }

    @Test
    void savedRefreshFlagsMigrateWithoutLosingTheOrdinaryCategorySwitch() throws Exception {
        var region = new RegionDefinition(UUID.randomUUID(), "Refresh", 0, 64, 0, 4, 64, 0);
        for (boolean storageOnly : List.of(false, true)) {
            for (String legacy : List.of("true", "false", "invalid", "")) {
                var parameters = Map.of("targetRef", "region:" + region.id(),
                        "categoryStorageOnlyEnabled", Boolean.toString(storageOnly),
                        "reuseSafeHandEnabled", legacy, "nearbyRouteEnabled", legacy);
                var step = TaskStep.action(UUID.randomUUID(), ContainerCacheRefresh.MODULE,
                        parameters);
                var task = new TaskDefinition(UUID.randomUUID(), "Refresh", List.of(step));
                var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(),
                        List.of(task), List.of(region), List.of());
                Path path = directory.resolve("refresh-" + storageOnly + "-" + legacy + ".json");
                Files.writeString(path, new Gson().toJson(config));
                var store = new ConfigStore(path);
                var restored = store.read();
                var clean = restored.config().tasks().getFirst().steps().getFirst();
                assertEquals(step.id(), clean.id());
                assertEquals(Map.of("targetRef", "region:" + region.id(),
                        "categoryStorageOnlyEnabled", Boolean.toString(storageOnly)),
                        clean.parameters());
                store.save(restored.revision(), restored.config());
                String saved = Files.readString(path);
                assertFalse(saved.contains("reuseSafeHandEnabled"));
                assertFalse(saved.contains("nearbyRouteEnabled"));
                assertEquals(restored.config(), new ConfigStore(path).read().config());
            }
        }
    }


    @Test
    void retiredClassificationSettingsAndStoredExperimentalStepsMigrateToOneBaseline()
            throws Exception {
        var initial = automaticConfiguration(false);
        var original = initial.autoClassifyTasks().getFirst();
        var definition = new AutoClassifyDefinition(original.id(), original.name(), false,
                false, true, null, null, original.cacheLocationRef(),
                original.classificationAreas(), original.collectionPriority(),
                original.overflowEnabled(), original.cleanupPolicy(), null,
                original.overflowDestinationRef(), null,
                AutoClassifyDefinition.Kind.CLASSIFICATION, null, null, null, null,
                original.remainderPolicy(), original.robotWarehouseMixedBoxes());
        var config = new AppConfig(initial.schemaVersion(), initial.coordinates(),
                AutoClassifyTaskFactory.tasks(definition, List.of()), initial.regions(),
                initial.categoryZones(), List.of(), List.of(), List.of(definition));
        var retired = List.of("sameCategoryBoxBatching", "classificationBaselineEnabled",
                "classificationFullTargetHandoffEnabled", "classificationPartialCacheOnDemandEnabled",
                "classificationCachedEmptyBoxSettlementEnabled");
        var gson = new com.google.gson.Gson();
        for (int mask = 0; mask < 32; mask++) {
            var json = gson.toJsonTree(config).getAsJsonObject();
            var legacy = json.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject();
            for (int index = 0; index < retired.size(); index++) {
                legacy.addProperty(retired.get(index), (mask & (1 << index)) != 0);
            }
            for (var value : json.getAsJsonArray("tasks")) {
                var task = value.getAsJsonObject();
                if (!task.get("id").getAsString().equals(definition.classificationTaskId()
                        .toString())) continue;
                var oldSteps = new com.google.gson.JsonArray();
                for (String module : List.of("CLASSIFY_MULTI_SOURCE_BATCHES",
                        "SUMMARIZE_CLASSIFICATION_BATCHES")) {
                    oldSteps.add(gson.toJsonTree(dev.stowling.v2.model.TaskStep.action(
                            UUID.randomUUID(), module, Map.of())));
                }
                task.add("steps", oldSteps);
            }
            Path path = directory.resolve("classification-mainline-" + mask + ".json");
            Files.writeString(path, gson.toJson(json));
            var store = new ConfigStore(path);
            var restored = store.read();
            assertEquals(definition, restored.config().autoClassifyTasks().getFirst());
            String normalized = gson.toJson(restored.config());
            for (String field : retired) assertFalse(normalized.contains(field));
            assertFalse(normalized.contains("CLASSIFY_MULTI_SOURCE_BATCHES"));
            assertFalse(normalized.contains("SUMMARIZE_CLASSIFICATION_BATCHES"));
            assertTrue(normalized.contains("WITHDRAW_ITEMS"));
            store.save(restored.revision(), restored.config());
            for (String field : retired) assertFalse(Files.readString(path).contains(field));
            assertEquals(restored.config(), new ConfigStore(path).read().config());
        }
    }

    @Test
    void upgradesSavedMixedAllocationWithoutResavingOrLosingIndependentDrafts() throws Exception {
        var global = new CategoryZoneOptions("selection", "marker", "mixed", "inside-box",
                "", false, "", false, null, 27);
        var local = new CategoryZoneOptions("selection", "marker", "mixed", "inside-box",
                "", false, "", false, null, 9);
        var area = new AreaBounds(0, 64, 0, 0, 64, 0);
        var packing = new AreaBounds(4, 64, 2, 4, 64, 2);
        for (boolean independent : List.of(false, true)) {
            var oldPolicy = new StoragePolicy(dev.stowling.v2.model.StorageMode.MIXED,
                    50, false, packing, false, independent ? 9 : 27, null);
            var binding = new CategoryBindingDefinition(UUID.randomUUID(), area, area,
                    "DIRECT_BLOCK_ID", List.of(), List.of(), null, oldPolicy,
                    false, "A", independent, local);
            var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Mixed", List.of(binding),
                    null, global);
            var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                    List.of(), List.of(), List.of(), List.of(zone));
            var saved = new Gson().toJsonTree(config).getAsJsonObject();
            saved.getAsJsonArray("categoryZones").get(0).getAsJsonObject()
                    .getAsJsonArray("bindings").get(0).getAsJsonObject()
                    .getAsJsonObject("storagePolicy").addProperty("allowPartialBoxes", false);
            Path path = directory.resolve("mixed-" + independent + ".json");
            String original = saved.toString();
            Files.writeString(path, original);
            var store = new ConfigStore(path);
            var snapshot = store.read();
            var restored = snapshot.config().categoryZones().getFirst();
            var effective = restored.bindings().getFirst().storagePolicy();
            assertTrue(effective.allowPartialBoxes());
            assertEquals(independent ? 9 : 27, effective.looseSlots());
            assertEquals(packing, effective.packingArea());
            assertEquals(global, restored.categoryOptions());
            assertEquals(local, restored.bindings().getFirst().categoryOptions());
            assertEquals(original, Files.readString(path));
            store.save(snapshot.revision(), snapshot.config());
            assertEquals(snapshot.config(), new ConfigStore(path).read().config());
        }
    }

    @Test
    void repairsSavedSupportBlockHeightsWithoutLosingSliceOffsetsOrShiftingTwice()
            throws Exception {
        for (boolean independent : List.of(false, true)) {
            var global = new CategoryZoneOptions("single", "marker", "bulk", "inside-box",
                    "", false, "", true, new BlockPosition(-8, -45, 2));
            var local = new CategoryZoneOptions("single", "marker", "bulk", "inside-box",
                    "", false, "", true, new BlockPosition(-8, -44, 2));
            var effective = independent ? local : global;
            var marker = new AreaBounds(0, 64, 0, 0, 64, 0);
            var storage = new AreaBounds(0, 65, 0, 0, 65, 0);
            var template = new CategoryBindingDefinition(UUID.randomUUID(), marker, storage,
                    "DIRECT_BLOCK_ID", List.of(), List.of(), effective.standingArea(null),
                    StoragePolicy.DEFAULT, false, "A", independent, local);
            var expansion = new CategoryZoneExpansionDefinition(
                    new AreaBounds(0, 64, 0, 2, 65, 0),
                    CategoryZoneExpansionAxis.X_POSITIVE, 1, 1);
            var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Standing",
                    CategoryZoneTiler.expand(expansion, List.of(template), global),
                    expansion, global);
            var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                    List.of(), List.of(), List.of(), List.of(zone));
            var saved = new Gson().toJsonTree(config).getAsJsonObject();
            var savedZone = saved.getAsJsonArray("categoryZones").get(0).getAsJsonObject();
            for (var element : savedZone.getAsJsonArray("bindings")) {
                var standing = element.getAsJsonObject().getAsJsonObject("standingArea");
                standing.addProperty("firstY", effective.standingPosition().y());
                standing.addProperty("secondY", effective.standingPosition().y());
            }
            Path path = directory.resolve("standing-" + independent + ".json");
            String original = saved.toString();
            Files.writeString(path, original);
            var store = new ConfigStore(path);
            var restored = store.read();
            assertEquals(config, restored.config());
            assertEquals(new AreaBounds(-6, effective.standingPosition().y() + 1, 2,
                    -6, effective.standingPosition().y() + 1, 2),
                    restored.config().categoryZones().getFirst().bindings().get(2).standingArea());
            assertEquals(original, Files.readString(path));
            store.save(restored.revision(), restored.config());
            assertEquals(config, new ConfigStore(path).read().config());
        }
    }

    @Test
    void preservesGlobalSettingsAndDisabledIndependentDraftsAfterRestart() throws Exception {
        Path path = directory.resolve("independent-settings.json");
        var area = new AreaBounds(0, 64, 0, 0, 64, 0);
        var global = new dev.stowling.v2.model.CategoryZoneOptions("selection", "marker", "bulk",
                "inside-box", "", false, "", false,
                new dev.stowling.domain.BlockPosition(1, 65, -2), 13);
        var local = new dev.stowling.v2.model.CategoryZoneOptions("selection", "container-item",
                "box", "inside-box", "", true, "minecraft:stone", true,
                new dev.stowling.domain.BlockPosition(4, 66, -5), 9);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(), area, area,
                "DIRECT_BLOCK_ID", List.of(), List.of(), null,
                dev.stowling.v2.model.StoragePolicy.DEFAULT, false, "A1", false, local);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "分类区", List.of(binding),
                null, global, new AreaBounds(-10, 60, -10, 10, 70, 10));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(), List.of(), List.of(), List.of(zone));
        var store = new ConfigStore(path);
        store.save(store.loadOrReset().revision(), config);
        var restored = new ConfigStore(path).read().config().categoryZones().getFirst();
        assertEquals(zone, restored);
        assertEquals(false, restored.bindings().getFirst().optionsEnabled());
        assertEquals("box", restored.bindings().getFirst().categoryOptions().storageMethod());
        assertEquals("bulk", restored.categoryOptions().storageMethod());
        assertEquals(13, restored.categoryOptions().bulkStacks());
        assertEquals(9, restored.bindings().getFirst().categoryOptions().bulkStacks());
        assertEquals(new dev.stowling.domain.BlockPosition(4, 66, -5),
                restored.bindings().getFirst().categoryOptions().standingPosition());
        var invalid = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        invalid.getAsJsonArray("categoryZones").get(0).getAsJsonObject()
                .getAsJsonObject("categoryOptions").getAsJsonObject("standingPosition")
                .addProperty("x", 0.5);
        Files.writeString(path, invalid.toString());
        assertThrows(V2ValidationException.class, () -> new ConfigStore(path).read());
    }

    @Test
    void rejectsFractionalAndOutOfRangeBulkStacksInSavedProfiles() throws Exception {
        Path path = directory.resolve("bulk-stacks.json");
        var area = new AreaBounds(0, 64, 0, 0, 64, 0);
        var options = new CategoryZoneOptions("selection", "marker", "mixed", "inside-box",
                "", false, "", null, null, 27);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(), area, area,
                "DIRECT_BLOCK_ID", List.of(), List.of(), null, StoragePolicy.DEFAULT,
                false, "A", false, options);
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "Bulk", List.of(binding),
                null, options);
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(), List.of(), List.of(), List.of(zone));
        for (String field : List.of("global", "local")) {
            for (String number : List.of("-1", "55", "1.5", "\"12\"", "true", "2147483648")) {
                var json = new Gson().toJsonTree(config).getAsJsonObject();
                var savedZone = json.getAsJsonArray("categoryZones").get(0).getAsJsonObject();
                var parent = field.equals("global") ? savedZone
                        : savedZone.getAsJsonArray("bindings").get(0).getAsJsonObject();
                parent.getAsJsonObject("categoryOptions")
                        .add("bulkStacks", JsonParser.parseString(number));
                Files.writeString(path, json.toString());
                assertThrows(V2ValidationException.class, () -> new ConfigStore(path).read());
            }
        }
    }

    @Test
    void acceptsBindingNamesInSavedFilesAndLegacyFilesWithoutNames() throws Exception {
        Path path = directory.resolve("binding-name.json");
        var area = new AreaBounds(0, 64, 0, 0, 64, 0);
        var binding = new CategoryBindingDefinition(UUID.randomUUID(), area, area,
                "DIRECT_BLOCK_ID");
        var zone = new CategoryZoneDefinition(UUID.randomUUID(), "分类区1", List.of(binding));
        var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(), List.of(), List.of(), List.of(zone));
        var legacy = new Gson().toJsonTree(config).getAsJsonObject();
        legacy.getAsJsonArray("categoryZones").get(0).getAsJsonObject()
                .getAsJsonArray("bindings").get(0).getAsJsonObject().remove("name");
        Files.writeString(path, legacy.toString());

        var store = new ConfigStore(path);
        var initial = store.read();
        assertEquals("", initial.config().categoryZones().getFirst().bindings().getFirst().name());
        var renamed = new CategoryBindingDefinition(binding.id(), area, area, binding.ruleId(),
                List.of(), List.of(), null, dev.stowling.v2.model.StoragePolicy.DEFAULT,
                false, "A1");
        var updated = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(), List.of(), List.of(),
                List.of(new CategoryZoneDefinition(zone.id(), zone.name(), List.of(renamed))));
        var saved = store.save(initial.revision(), updated);
        assertEquals("A1", saved.config().categoryZones().getFirst().bindings().getFirst().name());
        assertEquals(renamed, new ConfigStore(path).read().config()
                .categoryZones().getFirst().bindings().getFirst());

        var invalid = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        invalid.getAsJsonArray("categoryZones").get(0).getAsJsonObject()
                .getAsJsonArray("bindings").get(0).getAsJsonObject()
                .addProperty("unexpectedName", "typo");
        Files.writeString(path, invalid.toString());
        var error = assertThrows(V2ValidationException.class, () -> new ConfigStore(path).read());
        assertTrue(error.getMessage().contains("categoryZones[0].bindings[0]"));
        assertTrue(error.getMessage().contains("unexpectedName"));
        assertEquals(invalid.toString(), Files.readString(path));
    }

    @Test
    void cacheIntakePolicyPersistsAcrossConfigurationRestart() throws Exception {
        AppConfig initial = automaticConfiguration(false);
        var original = initial.autoClassifyTasks().getFirst();
        var policy = new dev.stowling.v2.model.CacheIntakePolicy(
                true, dev.stowling.v2.model.CacheIntakePolicy.Mode.CLASSIFY_FIRST, 65);
        var definition = new AutoClassifyDefinition(original.id(), original.name(), false,
                true, true, original.detectionCoordinateRef(), original.pickupRadius(),
                original.cacheLocationRef(), original.classificationAreas(),
                original.collectionPriority(), original.overflowEnabled(), original.cleanupPolicy(),
                null, original.overflowDestinationRef(), policy);
        var config = new AppConfig(initial.schemaVersion(), initial.coordinates(),
                AutoClassifyTaskFactory.tasks(definition, List.of()), initial.regions(),
                initial.categoryZones(), List.of(), List.of(), List.of(definition));
        Path path = directory.resolve("cache-intake.json");
        ConfigStore store = new ConfigStore(path);
        store.save(store.loadOrReset().revision(), config);

        assertEquals(policy, new ConfigStore(path).loadOrReset().config()
                .autoClassifyTasks().getFirst().cachePolicy());
        var legacy = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        legacy.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                .getAsJsonObject("cachePolicy").remove("enabled");
        Files.writeString(path, legacy.toString());
        var migrated = new ConfigStore(path).loadOrReset().config()
                .autoClassifyTasks().getFirst().cachePolicy();
        assertTrue(!migrated.enabled());
        assertEquals(65, migrated.thresholdPercent());
    }

    @Test
    void retiredRobotPriorityMigratesBothValuesAndIsNotSavedAgain() throws Exception {
        for (boolean retired : List.of(false, true)) {
            Path path = directory.resolve("retired-priority-" + retired + ".json");
            var root = JsonParser.parseString(new Gson().toJson(automaticConfiguration(true)))
                    .getAsJsonObject();
            root.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                    .getAsJsonObject("remainderPolicy")
                    .addProperty("robotWarehouseFirstEnabled", retired);
            for (var task : root.getAsJsonArray("tasks")) {
                for (var stepValue : task.getAsJsonObject().getAsJsonArray("steps")) {
                    var step = stepValue.getAsJsonObject();
                    if ("ROBOT_WAREHOUSE".equals(step.get("moduleId").getAsString())) {
                        step.getAsJsonObject("parameters")
                                .addProperty("robotWarehouseFirstEnabled", "" + retired);
                    }
                }
            }
            Files.writeString(path, root.toString());
            var store = new ConfigStore(path);
            var migrated = store.read();
            assertTrue(migrated.config().autoClassifyTasks().getFirst().overflowEnabled());
            assertFalse(new Gson().toJson(migrated.config()).contains("robotWarehouseFirstEnabled"));
            store.save(migrated.revision(), migrated.config());
            assertFalse(Files.readString(path).contains("robotWarehouseFirstEnabled"));
        }
    }

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
                List.of(), List.of(), null, dev.stowling.v2.model.StoragePolicy.DEFAULT, true,
                "石材缓存");
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
        var policy = new dev.stowling.v2.model.StoragePolicy(
                dev.stowling.v2.model.StorageMode.MIXED, 35, true, null, true, 19,
                original.detectionCoordinateRef());
        var originalZone = initial.categoryZones().getFirst();
        var binding = originalZone.bindings().getFirst();
        var updatedBinding = new CategoryBindingDefinition(binding.id(), binding.markerArea(),
                binding.storageArea(), binding.ruleId(), binding.ignoredMarkerBlockIds(),
                binding.containerOverrides(), binding.standingArea(), policy);
        var zone = new CategoryZoneDefinition(originalZone.id(), originalZone.name(),
                List.of(updatedBinding));
        var config = new AppConfig(initial.schemaVersion(), initial.coordinates(),
                initial.tasks(), initial.regions(), List.of(zone), List.of(), List.of(),
                initial.autoClassifyTasks());
        Path path = directory.resolve("partial-boxes.json");
        var store = new ConfigStore(path);
        store.save(store.loadOrReset().revision(), config);
        assertEquals(policy, new ConfigStore(path).loadOrReset().config().categoryZones()
                .getFirst().bindings().getFirst().storagePolicy());
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        json.getAsJsonArray("categoryZones").get(0).getAsJsonObject()
                .getAsJsonArray("bindings").get(0).getAsJsonObject()
                .getAsJsonObject("storagePolicy").addProperty("partialBoxDestinationRef",
                        "coordinate:" + UUID.randomUUID());
        String invalid = json.toString();
        Files.writeString(path, invalid);
        assertThrows(V2ValidationException.class, () -> new ConfigStore(path).loadOrReset());
        assertEquals(invalid, Files.readString(path));
    }

    @Test
    void discardsLegacyTaskStoragePoliciesWithoutChangingCoordinateSettings() throws Exception {
        Path path = directory.resolve("legacy-task-policy.json");
        var store = new ConfigStore(path);
        AppConfig initial = automaticConfiguration(false);
        store.save(store.loadOrReset().revision(), initial);
        var json = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var retired = JsonParser.parseString("""
                {"mode":"BOX_ONLY","loosePercent":25,"allowPartialBoxes":true}
                """).getAsJsonObject();
        retired.addProperty("partialBoxDestinationRef", "coordinate:" + UUID.randomUUID());
        json.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                .getAsJsonArray("classificationAreas").get(0).getAsJsonObject()
                .add("storagePolicy", retired);
        Files.writeString(path, json.toString());

        var restarted = new ConfigStore(path);
        var loaded = restarted.loadOrReset();
        assertEquals(initial.autoClassifyTasks(), loaded.config().autoClassifyTasks());
        assertEquals(initial.categoryZones(), loaded.config().categoryZones());
        CoordinateResourceMigration.migrate(restarted,
                dev.stowling.v2.model.StorageAutomationSettings.DEFAULT);
        restarted.save(loaded.revision(), loaded.config());
        var saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject()
                .getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                .getAsJsonArray("classificationAreas").get(0).getAsJsonObject();
        assertEquals(java.util.Set.of("categoryZoneRef"), saved.keySet());
        assertEquals(initial.categoryZones(), restarted.read().config().categoryZones());
    }

    @Test
    void automaticCleanupSelectionSurvivesRestartAndLegacyTasksDefaultToTheirCache()
            throws Exception {
        AppConfig initial = automaticConfiguration(false);
        var original = initial.autoClassifyTasks().getFirst();
        var policy = new dev.stowling.v2.model.TaskCleanupPolicy(original.detectionCoordinateRef(),
                dev.stowling.v2.model.TaskResumeMode.RESTART, List.of(), null,
                new dev.stowling.v2.model.EmptyBoxWarehousePolicy(
                        true, original.detectionCoordinateRef(), true, 9));
        var automatic = new AutoClassifyDefinition(original.id(), original.name(), original.enabled(),
                original.collectionEnabled(), original.classificationEnabled(),
                original.detectionCoordinateRef(), original.pickupRadius(), original.cacheLocationRef(),
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
        assertEquals(original.cacheLocationRef(), reopened.autoClassifyTasks().getFirst()
                .cleanupPolicy().destinationRef());
        assertTrue(reopened.tasks().stream().allMatch(task -> original.cacheLocationRef()
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
        var warehouse = new dev.stowling.v2.model.CoordinateCatalog.Warehouse(
                UUID.randomUUID(), "Overflow", List.of("coordinate:" + point.id()));
        var catalog = new dev.stowling.v2.model.CoordinateCatalog(
                List.of(), List.of(warehouse), Map.of());
        var definition = new AutoClassifyDefinition(UUID.randomUUID(), "Automatic",
                false, true, true, "coordinate:" + point.id(), 8, "region:" + region.id(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + zone.id())),
                TaskPriority.NORMAL, overflowEnabled, null, null,
                overflowEnabled ? "warehouse:" + warehouse.id() : null);
        return new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point),
                AutoClassifyTaskFactory.tasks(definition, List.of()), List.of(region),
                List.of(zone), List.of(), List.of(), List.of(definition), catalog);
    }

    @Test
    void rejectsOldSchemaWithoutOverwritingTheFile() throws Exception {
        Path path = directory.resolve("stowling.json");
        String content = "{\"schemaVersion\":15,\"coordinates\":[],\"tasks\":[]}";
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
    void keepsACurrentConfiguration() throws Exception {
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
                    "schemaVersion": 16,
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
                    "schemaVersion": 16,
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
                    "schemaVersion": 16,
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
                    "schemaVersion": 16,
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
                    "schemaVersion": 16,
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
    void rejectsInvalidCurrentSchemaWithoutOverwritingTheFile() throws Exception {
        Path path = directory.resolve("stowling.json");
        String content = """
                {
                    "schemaVersion": 16,
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
                    "schemaVersion": 16,
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
