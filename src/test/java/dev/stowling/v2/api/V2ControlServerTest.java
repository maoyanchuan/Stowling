package dev.stowling.v2.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.DroppedItem;
import dev.stowling.automation.DroppedItemObserverPort;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.control.V2ControlService;
import dev.stowling.v2.control.V2ControlServiceImpl;
import dev.stowling.v2.runtime.TaskRunManager;
import dev.stowling.v2.runtime.modules.TaskModuleRuntimeRegistry;
import dev.stowling.v2.runtime.modules.StandardTaskModuleFactories;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskStep;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V2ControlServerTest {
    @Test
    void automaticTaskAreaUsesLayoutRecognitionWithoutTaskLevelFields() throws Exception {
        var store = new ConfigStore(directory.resolve("layout-recognition.json"));
        var point = new dev.stowling.v2.model.CoordinatePoint(UUID.randomUUID(), "Drops", 0, 64, 0);
        var region = new RegionDefinition(UUID.randomUUID(), "Cache", 0, 64, 0, 1, 64, 1);
        var binding = new dev.stowling.v2.model.CategoryBindingDefinition(UUID.randomUUID(),
                new dev.stowling.v2.model.AreaBounds(5, 64, 0, 5, 64, 0),
                new dev.stowling.v2.model.AreaBounds(5, 64, 1, 5, 64, 1), "DIRECT_BLOCK_ID");
        var zone = new dev.stowling.v2.model.CategoryZoneDefinition(UUID.randomUUID(), "Storage",
                List.of(binding));
        store.save(store.loadOrReset().revision(), new dev.stowling.v2.model.AppConfig(
                dev.stowling.v2.model.AppConfig.CURRENT_SCHEMA_VERSION, List.of(point),
                List.of(), List.of(region), List.of(zone)));
        var service = new V2ControlServiceImpl(store, new TaskRunManager(new FailedNavigation()),
                List::of);
        String body = """
                {"name":"Automatic","enabled":false,"collectionEnabled":true,
                "classificationEnabled":true,"detection":{"coordinateRef":"coordinate:%s",
                "pickupRadius":8},"cacheRegionRef":"region:%s",
                "classificationAreas":[{"categoryZoneRef":"category-zone:%s"}],
                "collectionPriority":"NORMAL"}
                """.formatted(point.id(), region.id(), zone.id());
        try (Fixture fixture = new Fixture(service)) {
            assertEquals(401, fixture.send("/v1/auto-classify-tasks", "POST", body, false)
                    .statusCode());
            var response = fixture.send("/v1/auto-classify-tasks", "POST", body, true);
            assertEquals(201, response.statusCode());
            var area = object(response).getAsJsonObject("item")
                    .getAsJsonArray("classificationAreas").get(0).getAsJsonObject();
            assertEquals("category-zone:" + zone.id(), area.get("categoryZoneRef").getAsString());
            assertTrue(!area.has("ruleId"));
            assertTrue(!area.has("markerSources"));
        }
    }

    @Test
    void detailedDiagnosticsRequireAuthenticationAndValidateTheirReadCursor() throws Exception {
        ConfigStore store = new ConfigStore(directory.resolve("diagnostics.json"));
        store.loadOrReset();
        TaskRunManager runs = new TaskRunManager(new FailedNavigation());
        UUID owner = UUID.randomUUID();
        runs.diagnostics().append(owner, "TASK", "2.2", "DEPOSIT", "WAIT", "confirm",
                "planned=64; actual=0", 1200, false);
        runs.diagnostics().append(owner, "TASK", "2.2", "DEPOSIT", "RESYNC", "reopen",
                "target=1,2,3", 1300, false);
        try (Fixture fixture = new Fixture(new V2ControlServiceImpl(store, runs, List::of))) {
            assertEquals(401, fixture.send("/v1/task-diagnostics", "GET", null, false).statusCode());
            assertEquals(401, fixture.send("/v1/task-diagnostics/since/0", "GET", null, false)
                    .statusCode());
            var full = object(fixture.send("/v1/task-diagnostics", "GET", null, true));
            assertEquals(2, full.getAsJsonArray("events").size());
            var next = object(fixture.send("/v1/task-diagnostics/since/1", "GET", null, true));
            assertEquals(1, next.getAsJsonArray("events").size());
            assertEquals("RESYNC", next.getAsJsonArray("events").get(0).getAsJsonObject()
                    .get("kind").getAsString());
            runs.diagnostics().append(UUID.randomUUID(), "TASK", "1", "MOVE", "STATE", "run",
                    "other-execution", 0, false);
            String runPath = "/v1/task-diagnostics/run/" + owner;
            assertEquals(401, fixture.send(runPath, "GET", null, false).statusCode());
            var selected = object(fixture.send(runPath, "GET", null, true));
            assertEquals(2, selected.getAsJsonArray("events").size());
            assertEquals(owner.toString(), selected.getAsJsonArray("events").get(0)
                    .getAsJsonObject().get("runId").getAsString());
            assertEquals(400, fixture.send("/v1/task-diagnostics/run/invalid", "GET", null, true)
                    .statusCode());
            assertEquals(405, fixture.send(runPath, "POST", "{}", true).statusCode());
            assertEquals(400, fixture.send("/v1/task-diagnostics/since/-1", "GET", null, true)
                    .statusCode());
            assertEquals(405, fixture.send("/v1/task-diagnostics", "POST", "{}", true)
                    .statusCode());
        }
    }
    @Test
    void backpackAllocationRequiresAuthenticationAndRejectsStaleOrRunningChanges()
            throws Exception {
        ConfigStore store = new ConfigStore(directory.resolve("backpack.json"));
        store.loadOrReset();
        var navigation = new FailedNavigation();
        var base = TaskModuleRuntimeRegistry.unavailablePorts(navigation);
        DroppedItemObserverPort drops = new DroppedItemObserverPort() {
            @Override
            public List<DroppedItem> observe(BlockPosition center, int radius) {
                return List.of();
            }

            @Override
            public List<DroppedItem> observe(RegionDefinition region) {
                return List.of();
            }

            @Override
            public Optional<Map<String, Integer>> playerItemCounts() {
                return Optional.of(Map.of("minecraft:stone", 64));
            }
        };
        var registry = new TaskModuleRuntimeRegistry(navigation, drops, base.containerRegions(),
                base.containerTransfers(), base.containerWithdrawals(), base.containerInspections(),
                base.categoryMarkerCache(), base.foodConsumption(), base.categoryZones(),
                StandardTaskModuleFactories.create());
        var runs = new TaskRunManager(navigation, registry);
        runs.tick();
        try (Fixture fixture = new Fixture(new V2ControlServiceImpl(store, runs, List::of))) {
            for (String asset : List.of("/backpack.html", "/backpack.js", "/backpack-model.js")) {
                assertEquals(200, fixture.send(asset, "GET", null, false).statusCode());
            }
            assertEquals(401, fixture.send("/v1/player-inventory", "GET", null, false).statusCode());
            JsonObject view = object(fixture.send("/v1/player-inventory", "GET", null, true));
            JsonObject allocation = new JsonObject();
            allocation.addProperty("revision", view.get("revision").getAsString());
            allocation.addProperty("key", "minecraft:stone");
            allocation.addProperty("keepCount", 16);
            assertEquals(401, fixture.send("/v1/player-inventory", "PUT",
                    allocation.toString(), false).statusCode());
            HttpResponse<String> saved = fixture.send("/v1/player-inventory", "PUT",
                    allocation.toString(), true);
            assertEquals(200, saved.statusCode());
            JsonObject item = object(saved).getAsJsonArray("items").get(0).getAsJsonObject();
            assertEquals(16, item.get("protectedCount").getAsInt());
            assertEquals(48, item.get("classificationCount").getAsInt());
            assertEquals(409, fixture.send("/v1/player-inventory", "PUT",
                    allocation.toString(), true).statusCode());
            allocation.addProperty("revision", object(saved).get("revision").getAsString());
            allocation.addProperty("keepCount", -1);
            assertEquals(400, fixture.send("/v1/player-inventory", "PUT",
                    allocation.toString(), true).statusCode());
            allocation.addProperty("keepCount", 0);
            runs.start(new TaskDefinition(UUID.randomUUID(), "Busy", List.of(TaskStep.action(
                    UUID.randomUUID(), "SYNC_PLAYER_INVENTORY", Map.of()))), List.of());
            assertEquals(409, fixture.send("/v1/player-inventory", "PUT",
                    allocation.toString(), true).statusCode());
        }
    }

    @Test
    void acceptsWholeStorageForRotatedSlicesAndKeepsItOnEveryCopy() throws Exception {
        try (Fixture fixture = fixture()) {
            JsonObject payload = JsonParser.parseString("""
                    {"name":"Rotated","bindings":[{
                    "markerFirstX":11,"markerFirstY":-40,"markerFirstZ":46,
                    "markerSecondX":10,"markerSecondY":-40,"markerSecondZ":46,
                    "storageFirstX":10,"storageFirstY":-38,"storageFirstZ":46,
                    "storageSecondX":10,"storageSecondY":-36,"storageSecondZ":47,
                    "ruleId":"MARKER_CONTAINER_CONTENTS","wholeStorage":true}],
                    "expansion":{"firstX":10,"firstY":-40,"firstZ":46,
                    "secondX":11,"secondY":-36,"secondZ":51,
                    "direction":"Z_POSITIVE","step":2}}
                    """).getAsJsonObject();
            assertEquals(401, fixture.send("/v1/category-zones", "POST",
                    payload.toString(), false).statusCode());
            HttpResponse<String> created = fixture.send("/v1/category-zones", "POST",
                    payload.toString(), true);
            assertEquals(201, created.statusCode());
            JsonArray bindings = object(created).getAsJsonArray("categoryZones")
                    .get(0).getAsJsonObject().getAsJsonArray("bindings");
            assertEquals(3, bindings.size());
            for (JsonElement binding : bindings) {
                assertTrue(binding.getAsJsonObject().get("wholeStorage").getAsBoolean());
            }
            JsonObject third = bindings.get(2).getAsJsonObject().getAsJsonObject("storageArea");
            assertEquals(50, third.get("firstZ").getAsInt());
            assertEquals(51, third.get("secondZ").getAsInt());
            payload.getAsJsonArray("bindings").get(0).getAsJsonObject()
                    .addProperty("wholeStorage", "true");
            assertEquals(400, fixture.send("/v1/category-zones", "POST",
                    payload.toString(), true).statusCode());
        }
    }

    @Test
    void acceptsAndPersistsTheOptionalLooseRefillSwitch() throws Exception {
        try (Fixture fixture = fixture()) {
            JsonObject payload = JsonParser.parseString(categoryZone("Refill")).getAsJsonObject();
            JsonObject policy = JsonParser.parseString("""
                    {"mode":"MIXED","loosePercent":50,"allowPartialBoxes":false,
                    "refillLoose":true}
                    """).getAsJsonObject();
            payload.getAsJsonArray("bindings").get(0).getAsJsonObject()
                    .add("storagePolicy", policy);
            assertEquals(401, fixture.send("/v1/category-zones", "POST",
                    payload.toString(), false).statusCode());
            assertEquals(201, fixture.send("/v1/category-zones", "POST",
                    payload.toString(), true).statusCode());
            JsonObject saved = object(fixture.send("/v1/category-zones", "GET", null, true))
                    .getAsJsonArray("categoryZones").get(0).getAsJsonObject()
                    .getAsJsonArray("bindings").get(0).getAsJsonObject()
                    .getAsJsonObject("storagePolicy");
            assertTrue(saved.get("refillLoose").getAsBoolean());
        }
    }

    @Test
    void warehouseInventoryRequiresAuthenticationAndServesItsBrowserAssets() throws Exception {
        try (Fixture fixture = fixture()) {
            assertEquals(401, fixture.send(
                    "/v1/warehouse-inventory", "GET", null, false).statusCode());
            assertEquals(200, fixture.send(
                    "/v1/warehouse-inventory", "GET", null, true).statusCode());
            assertEquals(405, fixture.send(
                    "/v1/warehouse-inventory", "POST", "{}", true).statusCode());
            for (String asset : List.of("/storage.html", "/storage.js", "/warehouse-model.js")) {
                assertEquals(200, fixture.send(asset, "GET", null, false).statusCode());
            }
        }
    }

    private static final String TOKEN = "test-token-that-is-long-enough";

    @TempDir
    Path directory;

    @Test
    void enforcesAuthenticationSecurityHeadersAndAllowedMethods() throws Exception {
        try (Fixture fixture = fixture()) {
            HttpResponse<String> page = fixture.send("/", "GET", null, false);
            assertEquals(200, page.statusCode());
            assertTrue(page.headers()
                    .firstValue("Content-Security-Policy")
                    .orElse("")
                    .contains("default-src 'self'"));
            assertEquals(
                    "nosniff",
                    page.headers()
                            .firstValue("X-Content-Type-Options")
                            .orElse("")
            );
            HttpResponse<String> triggerModel = fixture.send(
                    "/v2-trigger-model.js",
                    "GET",
                    null,
                    false
            );
            assertEquals(200, triggerModel.statusCode());
            assertTrue(triggerModel.body().contains(
                    "window.StowlingTriggerModel"
            ));

            for (String path : List.of(
                    "/auto-classify-task-model.js",
                    "/auto-classify-ui.js"
            )) {
                HttpResponse<String> asset = fixture.send(
                        path,
                        "GET",
                        null,
                        false
                );
                assertEquals(200, asset.statusCode(), path);
            }
            HttpResponse<String> autoClassify = fixture.send(
                    "/v1/auto-classify-tasks",
                    "GET",
                    null,
                    true
            );
            assertEquals(200, autoClassify.statusCode());
            assertTrue(autoClassify.body().contains("\"items\""));

            HttpResponse<String> unauthorized = fixture.send(
                    "/v1/status",
                    "GET",
                    null,
                    false
            );
            assertEquals(401, unauthorized.statusCode());
            assertEquals(
                    "Bearer",
                    unauthorized.headers()
                            .firstValue("WWW-Authenticate")
                            .orElse("")
            );
            HttpResponse<String> unauthorizedFolders = fixture.send(
                    "/v1/task-folders",
                    "GET",
                    null,
                    false
            );
            assertEquals(401, unauthorizedFolders.statusCode());
            HttpResponse<String> unauthorizedAutoClassify = fixture.send(
                    "/v1/auto-classify-tasks",
                    "POST",
                    "{}",
                    false
            );
            assertEquals(401, unauthorizedAutoClassify.statusCode());

            HttpResponse<String> wrongMethod = fixture.send(
                    "/v1/coordinates",
                    "PATCH",
                    "",
                    true
            );
            assertEquals(405, wrongMethod.statusCode());
            assertEquals(
                    "GET, POST",
                    wrongMethod.headers().firstValue("Allow").orElse("")
            );
        }
    }

    @Test
    void servesInventoryWebRoutesAndTaskAssets() throws Exception {
        try (Fixture fixture = fixture()) {
            for (String path : List.of(
                    "/storage.html",
                    "/storage.js"
            )) {
                HttpResponse<String> response = fixture.send(
                        path,
                        "GET",
                        null,
                        true
                );
                assertEquals(200, response.statusCode(), path);
            }

            HttpResponse<String> tasksPage = fixture.send(
                    "/tasks.html",
                    "GET",
                    null,
                    false
            );
            assertEquals(200, tasksPage.statusCode());

            HttpResponse<String> taskMonitorModel = fixture.send(
                    "/task-monitor-model.js",
                    "GET",
                    null,
                    false
            );
            assertEquals(200, taskMonitorModel.statusCode());
            assertEquals(
                    "text/javascript; charset=utf-8",
                    taskMonitorModel.headers()
                            .firstValue("Content-Type")
                            .orElse("")
            );

            HttpResponse<String> moveTaskModel = fixture.send(
                    "/move-task-model.js",
                    "GET",
                    null,
                    false
            );
            assertEquals(200, moveTaskModel.statusCode());
            assertEquals(
                    "text/javascript; charset=utf-8",
                    moveTaskModel.headers()
                            .firstValue("Content-Type")
                            .orElse("")
            );
            assertTrue(moveTaskModel.body().contains(
                    "window.StowlingMoveTaskModel"
            ));
        }
    }

    @Test
    void removesEveryLegacyRoute() throws Exception {
        try (Fixture fixture = fixture()) {
            for (String path : List.of(
                    "/v1/config/coordinates",
                    "/v1/task-definitions",
                    "/v1/task-definitions/example",
                    "/v1/containers",
                    "/v1/pickup"
            )) {
                HttpResponse<String> response = fixture.send(
                        path,
                        "GET",
                        null,
                        true
                );
                assertEquals(404, response.statusCode(), path);
            }
        }
    }

    @Test
    void managesTaskFoldersAndClearsTaskAssignmentsOnDelete() throws Exception {
        try (Fixture fixture = fixture()) {
            HttpResponse<String> createdFolder = fixture.send(
                    "/v1/task-folders",
                    "POST",
                    "{\"name\":\"Farm\"}",
                    true
            );
            assertEquals(201, createdFolder.statusCode());
            JsonObject folder = object(createdFolder)
                    .getAsJsonArray("folders")
                    .get(0)
                    .getAsJsonObject();
            String folderId = folder.get("id").getAsString();
            assertEquals(
                    "Farm",
                    folder.get("name").getAsString()
            );

            HttpResponse<String> createdTask = fixture.send(
                    "/v1/tasks",
                    "POST",
                    """
                            {"name":"Harvest","folderId":"%s","steps":[{
                            "moduleId":"MOVE_TO_COORDINATE",
                            "parameters":{"targetRef":"output:container"}}]}
                            """.formatted(folderId),
                    true
            );
            assertEquals(201, createdTask.statusCode());
            String createdTaskId = object(createdTask)
                    .get("taskId")
                    .getAsString();
            HttpResponse<String> listedTasks = fixture.send(
                    "/v1/tasks",
                    "GET",
                    null,
                    true
            );
            JsonObject task = object(listedTasks).getAsJsonArray("tasks")
                    .get(0)
                    .getAsJsonObject();
            assertEquals(folderId, task.get("folderId").getAsString());
            assertEquals(createdTaskId, task.get("id").getAsString());
            String revision = object(listedTasks).get("revision").getAsString();

            HttpResponse<String> renamed = fixture.send(
                    "/v1/task-folders/" + folderId,
                    "PUT",
                    """
                            {"revision":"%s","name":"Fields"}
                            """.formatted(revision),
                    true
            );
            assertEquals(200, renamed.statusCode());
            JsonObject renamedFolder = object(renamed)
                    .getAsJsonArray("folders")
                    .get(0)
                    .getAsJsonObject();
            assertEquals(
                    "Fields",
                    renamedFolder.get("name").getAsString()
            );

            HttpResponse<String> deleted = fixture.send(
                    "/v1/task-folders/" + folderId,
                    "DELETE",
                    """
                            {"revision":"%s"}
                            """.formatted(object(renamed)
                                    .get("revision")
                                    .getAsString()),
                    true
            );
            assertEquals(200, deleted.statusCode());
            assertEquals(0, object(deleted).getAsJsonArray("folders").size());
            JsonObject remainingTask = object(fixture.send(
                    "/v1/tasks",
                    "GET",
                    null,
                    true
            )).getAsJsonArray("tasks").get(0).getAsJsonObject();
            assertTrue(remainingTask.has("folderId"));
            assertTrue(remainingTask.get("folderId").isJsonNull());
        }
    }

    @Test
    void exposesSevenActiveTaskModulesAndLegacyCompatibility()
            throws Exception {
        try (Fixture fixture = fixture()) {
            JsonArray modules = object(fixture.send(
                    "/v1/task-modules",
                    "GET",
                    null,
                    true
            )).getAsJsonArray("modules");
            int active = 0;
            boolean unifiedCollect = false;
            boolean waitDeprecated = false;
            for (JsonElement element : modules) {
                JsonObject module = element.getAsJsonObject();
                boolean deprecated = module.get("deprecated").getAsBoolean();
                if (!deprecated) {
                    active++;
                }
                String id = module.get("id").getAsString();
                if ("COLLECT_DROPPED_ITEMS".equals(id)) {
                    unifiedCollect = module.getAsJsonArray("parameters")
                            .get(0)
                            .getAsJsonObject()
                            .get("kind")
                            .getAsString()
                            .equals("area-reference");
                }
                if ("WAIT_FOR_DROPPED_ITEMS".equals(id)) {
                    waitDeprecated = deprecated;
                }
            }

            assertEquals(7, active);
            assertTrue(unifiedCollect);
            assertTrue(waitDeprecated);
        }
    }

    @Test
    void supportsCoordinateAndTaskCrudWithRevisions() throws Exception {
        try (Fixture fixture = fixture()) {
            JsonObject first = object(fixture.send(
                    "/v1/coordinates",
                    "POST",
                    coordinate("Home", 1, 64, 2),
                    true
            ));
            JsonObject second = object(fixture.send(
                    "/v1/coordinates",
                    "POST",
                    coordinate("Mine", 40, 70, -8),
                    true
            ));
            String firstId = first.getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            String secondId = second.getAsJsonArray("coordinates")
                    .get(1)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            String revision = second.get("revision").getAsString();
            String taskBody = task(
                    "Travel",
                    List.of(firstId, secondId)
            );

            HttpResponse<String> taskCreated = fixture.send(
                    "/v1/tasks",
                    "POST",
                    taskBody,
                    true
            );
            assertEquals(201, taskCreated.statusCode());
            JsonObject taskList = object(taskCreated);
            JsonObject task = taskList.getAsJsonArray("tasks")
                    .get(0)
                    .getAsJsonObject();
            String taskId = task.get("id").getAsString();
            assertEquals(2, task.getAsJsonArray("steps").size());
            assertEquals("NORMAL", task.get("priority").getAsString());
            assertEquals(false, task.get("repeatWhenIdle").getAsBoolean());
            assertEquals(2, object(fixture.send(
                    "/v1/tasks/" + taskId,
                    "GET",
                    null,
                    true
            )).getAsJsonArray("steps").size());

            HttpResponse<String> stale = fixture.send(
                    "/v1/coordinates/" + firstId,
                    "PUT",
                    """
                            {"revision":"stale","name":"Changed","x":2,"y":65,"z":3}
                            """,
                    true
            );
            assertEquals(409, stale.statusCode());

            revision = taskList.get("revision").getAsString();
            HttpResponse<String> inUse = fixture.send(
                    "/v1/coordinates/" + firstId,
                    "DELETE",
                    "{\"revision\":\"" + revision + "\"}",
                    true
            );
            assertEquals(409, inUse.statusCode());
            JsonArray references = object(inUse)
                    .getAsJsonObject("error")
                    .getAsJsonArray("taskNames");
            assertEquals("Travel", references.get(0).getAsString());

            HttpResponse<String> renamed = fixture.send(
                    "/v1/coordinates/" + firstId,
                    "PUT",
                    """
                            {"revision":"%s","name":"Base","x":3,"y":66,"z":4}
                            """.formatted(revision),
                    true
            );
            assertEquals(200, renamed.statusCode());
            revision = object(renamed).get("revision").getAsString();
            assertEquals(
                    firstId,
                    object(fixture.send(
                            "/v1/tasks/" + taskId,
                            "GET",
                            null,
                            true
                    )).getAsJsonArray("steps")
                            .get(0)
                            .getAsJsonObject()
                            .getAsJsonObject("parameters")
                            .get("coordinateId")
                            .getAsString()
            );

            HttpResponse<String> taskDeleted = fixture.send(
                    "/v1/tasks/" + taskId,
                    "DELETE",
                    "{\"revision\":\"" + revision + "\"}",
                    true
            );
            assertEquals(200, taskDeleted.statusCode());
            revision = object(taskDeleted).get("revision").getAsString();
            assertEquals(
                    200,
                    fixture.send(
                            "/v1/coordinates/" + firstId,
                            "DELETE",
                            "{\"revision\":\"" + revision + "\"}",
                            true
                    ).statusCode()
            );
        }
    }

    @Test
    void supportsTaskSchedulingFieldsAndRunPriorityOverride() throws Exception {
        try (Fixture fixture = fixture()) {
            JsonObject coordinates = object(fixture.send(
                    "/v1/coordinates",
                    "POST",
                    coordinate("Home", 1, 64, 2),
                    true
            ));
            String coordinateId = coordinates.getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            String body = """
                    {"name":"Classify","priority":"BACKGROUND",
                    "repeatWhenIdle":true,"steps":[
                    {"moduleId":"MOVE_TO_COORDINATE","parameters":{
                    "coordinateId":"%s"}}]}
                    """.formatted(coordinateId);
            JsonObject taskList = object(fixture.send(
                    "/v1/tasks",
                    "POST",
                    body,
                    true
            ));
            JsonObject task = taskList.getAsJsonArray("tasks")
                    .get(0)
                    .getAsJsonObject();

            assertEquals("BACKGROUND", task.get("priority").getAsString());
            assertTrue(task.get("repeatWhenIdle").getAsBoolean());
            JsonObject run = object(fixture.send(
                    "/v1/tasks/" + task.get("id").getAsString() + "/runs",
                    "POST",
                    "{\"priority\":\"URGENT\"}",
                    true
            ));
            assertEquals("URGENT", run.get("priority").getAsString());
            assertEquals("RUNNING", run.get("state").getAsString());
        }
    }

    @Test
    void supportsIndependentTaskTriggersAndReturnsIdleStatus()
            throws Exception {
        try (Fixture fixture = fixture()) {
            JsonObject coordinates = object(fixture.send(
                    "/v1/coordinates",
                    "POST",
                    coordinate("Drops", 1, 64, 2),
                    true
            ));
            String coordinateId = coordinates.getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            String body = """
                    {"name":"Pickup","priority":"NORMAL",
                    "steps":[{"moduleId":"MOVE_TO_COORDINATE",
                    "parameters":{"coordinateId":"%s"}}]}
                    """.formatted(coordinateId);

            HttpResponse<String> created = fixture.send(
                    "/v1/tasks",
                    "POST",
                    body,
                    true
            );
            assertEquals(201, created.statusCode());
            String taskId = object(created)
                    .getAsJsonArray("tasks")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            String triggerBody = """
                    {"name":"Pickup drops","type":"DROPPED_ITEMS",
                    "targetTaskId":"%s","priority":"NORMAL",
                    "intervalTicks":20,"cooldownTicks":100,
                    "coordinateId":"%s",
                    "itemFilter":"minecraft:oak_log"}
                    """.formatted(taskId, coordinateId);
            HttpResponse<String> triggerCreated = fixture.send(
                    "/v1/task-triggers",
                    "POST",
                    triggerBody,
                    true
            );
            assertEquals(201, triggerCreated.statusCode());
            JsonObject trigger = object(triggerCreated)
                    .getAsJsonArray("triggers")
                    .get(0)
                    .getAsJsonObject();
            assertEquals("DROPPED_ITEMS", trigger.get("type").getAsString());
            assertEquals(20, trigger.get("intervalTicks").getAsInt());
            assertEquals("NORMAL", trigger.get("priority").getAsString());

            JsonObject runs = object(fixture.send(
                    "/v1/task-runs",
                    "GET",
                    null,
                    true
            ));
            JsonObject status = runs.getAsJsonArray("triggers")
                    .get(0)
                    .getAsJsonObject();
            assertEquals("IDLE", status.get("state").getAsString());
            assertEquals("Pickup", status.get("targetTaskName").getAsString());
        }
    }

    @Test
    void supportsCacheUpdatedTriggersWithARegionReference()
            throws Exception {
        try (Fixture fixture = fixture()) {
            JsonObject coordinates = object(fixture.send(
                    "/v1/coordinates",
                    "POST",
                    coordinate("Cache", 1, 64, 2),
                    true
            ));
            String coordinateId = coordinates.getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            JsonObject regions = object(fixture.send(
                    "/v1/regions",
                    "POST",
                    """
                            {"name":"Cache area","firstX":0,"firstY":64,
                            "firstZ":0,"secondX":16,"secondY":72,"secondZ":16}
                            """,
                    true
            ));
            String regionId = regions.getAsJsonArray("regions")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            JsonObject tasks = object(fixture.send(
                    "/v1/tasks",
                    "POST",
                    """
                            {"name":"Sort cache","priority":"NORMAL","steps":[
                            {"moduleId":"MOVE_TO_COORDINATE","parameters":{
                            "coordinateId":"%s"}}]}
                            """.formatted(coordinateId),
                    true
            ));
            String taskId = tasks.getAsJsonArray("tasks")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();

            HttpResponse<String> created = fixture.send(
                    "/v1/task-triggers",
                    "POST",
                    """
                            {"name":"Sort after cache update",
                            "type":"CACHE_UPDATED","targetTaskId":"%s",
                            "priority":"URGENT","intervalTicks":20,
                            "cooldownTicks":100,"cacheRegionId":"%s"}
                            """.formatted(taskId, regionId),
                    true
            );

            assertEquals(201, created.statusCode());
            JsonObject createdBody = object(created);
            JsonObject trigger = createdBody
                    .getAsJsonArray("triggers")
                    .get(0)
                    .getAsJsonObject();
            assertEquals("CACHE_UPDATED", trigger.get("type").getAsString());
            assertEquals(regionId, trigger.get("cacheRegionId").getAsString());
            assertEquals("URGENT", trigger.get("priority").getAsString());

            String triggerId = trigger.get("id").getAsString();
            HttpResponse<String> updated = fixture.send(
                    "/v1/task-triggers/" + triggerId,
                    "PUT",
                    """
                            {"revision":"%s","name":"Sort cache again",
                            "type":"CACHE_UPDATED","targetTaskId":"%s",
                            "priority":"NORMAL","intervalTicks":40,
                            "cooldownTicks":100,"cacheRegionId":"%s"}
                            """.formatted(
                                    createdBody.get("revision").getAsString(),
                                    taskId,
                                    regionId
                            ),
                    true
            );
            assertEquals(200, updated.statusCode());
            JsonObject updatedTrigger = object(updated)
                    .getAsJsonArray("triggers")
                    .get(0)
                    .getAsJsonObject();
            assertEquals(
                    "Sort cache again",
                    updatedTrigger.get("name").getAsString()
            );
        }
    }

    @Test
    void supportsOptionalCoordinateDimensionAndRadius() throws Exception {
        try (Fixture fixture = fixture()) {
            HttpResponse<String> created = fixture.send(
                    "/v1/coordinates",
                    "POST",
                    """
                            {"name":"Home","x":1,"y":64,"z":2,
                            "dimension":"minecraft:overworld","radius":4}
                            """,
                    true
            );
            assertEquals(201, created.statusCode());
            JsonObject coordinate = object(created)
                    .getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject();
            assertEquals(
                    "minecraft:overworld",
                    coordinate.get("dimension").getAsString()
            );
            assertEquals(4, coordinate.get("radius").getAsInt());

            String id = coordinate.get("id").getAsString();
            HttpResponse<String> disabled = fixture.send(
                    "/v1/coordinates/" + id,
                    "PUT",
                    """
                            {"revision":"%s","name":"Home","x":1,"y":64,"z":2,
                            "dimension":null,"radius":null}
                            """.formatted(object(created).get("revision").getAsString()),
                    true
            );
            JsonObject updated = object(disabled)
                    .getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject();
            assertTrue(updated.get("dimension").isJsonNull());
            assertTrue(updated.get("radius").isJsonNull());
        }
    }

    @Test
    void supportsRegionCrud() throws Exception {
        try (Fixture fixture = fixture()) {
            HttpResponse<String> created = fixture.send(
                    "/v1/regions",
                    "POST",
                    """
                            {"name":"Farm","firstX":0,"firstY":64,"firstZ":0,
                            "secondX":10,"secondY":70,"secondZ":10}
                            """,
                    true
            );
            assertEquals(201, created.statusCode());
            JsonObject createdList = object(created);
            JsonObject region = createdList.getAsJsonArray("regions")
                    .get(0)
                    .getAsJsonObject();
            String id = region.get("id").getAsString();
            assertEquals(10, region.get("secondX").getAsInt());

            HttpResponse<String> updated = fixture.send(
                    "/v1/regions/" + id,
                    "PUT",
                    """
                            {"revision":"%s","name":"Field",
                            "firstX":-5,"firstY":60,"firstZ":-5,
                            "secondX":15,"secondY":72,"secondZ":15}
                            """.formatted(createdList.get("revision").getAsString()),
                    true
            );
            assertEquals(200, updated.statusCode());
            JsonObject updatedList = object(updated);
            assertEquals(
                    "Field",
                    updatedList.getAsJsonArray("regions")
                            .get(0)
                            .getAsJsonObject()
                            .get("name")
                            .getAsString()
            );

            assertEquals(
                    200,
                    fixture.send(
                            "/v1/regions/" + id,
                            "DELETE",
                            """
                                    {"revision":"%s"}
                                    """.formatted(
                                            updatedList.get("revision").getAsString()
                                    ),
                            true
                    ).statusCode()
            );
        }
    }

    @Test
    void rejectsUnknownModulesParametersAndCoordinates() throws Exception {
        try (Fixture fixture = fixture()) {
            JsonObject created = object(fixture.send(
                    "/v1/coordinates",
                    "POST",
                    coordinate("Home", 0, 64, 0),
                    true
            ));
            String id = created.getAsJsonArray("coordinates")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            String unknownModule = """
                    {"name":"Unknown","steps":[{"moduleId":"UNKNOWN",
                    "parameters":{"coordinateId":"%s"}}]}
                    """.formatted(id);
            String extraParameter = """
                    {"name":"Extra","steps":[{
                    "moduleId":"MOVE_TO_COORDINATE",
                    "parameters":{"coordinateId":"%s","radius":"3"}}]}
                    """.formatted(id);
            String missingCoordinate = """
                    {"name":"Missing","steps":[{
                    "moduleId":"MOVE_TO_COORDINATE",
                    "parameters":{"coordinateId":"%s"}}]}
                    """.formatted("00000000-0000-0000-0000-000000000000");

            assertEquals(
                    400,
                    fixture.send("/v1/tasks", "POST", unknownModule, true)
                            .statusCode()
            );
            assertEquals(
                    400,
                    fixture.send("/v1/tasks", "POST", extraParameter, true)
                            .statusCode()
            );
            assertEquals(
                    400,
                    fixture.send("/v1/tasks", "POST", missingCoordinate, true)
                            .statusCode()
            );
        }
    }

    @Test
    void rejectsDuplicateAndTrailingJsonFields() throws Exception {
        try (Fixture fixture = fixture()) {
            String duplicate = """
                    {"name":"Home","name":"Other","x":1,"y":64,"z":2}
                    """;
            String trailing = """
                    {"name":"Home","x":1,"y":64,"z":2} {}
                    """;

            assertEquals(
                    400,
                    fixture.send(
                            "/v1/coordinates",
                            "POST",
                            duplicate,
                            true
                    ).statusCode()
            );
            assertEquals(
                    400,
                    fixture.send(
                            "/v1/coordinates",
                            "POST",
                            trailing,
                            true
                    ).statusCode()
            );
        }
    }

    @Test
    void supportsCategoryZoneCrudAndListsRules() throws Exception {
        try (Fixture fixture = fixture()) {
            HttpResponse<String> created = fixture.send(
                    "/v1/category-zones",
                    "POST",
                    categoryZone("A"),
                    true
            );
            assertEquals(201, created.statusCode());
            JsonObject createdBody = object(created);
            JsonObject zone = createdBody.getAsJsonArray("categoryZones")
                    .get(0)
                    .getAsJsonObject();
            String zoneId = zone.get("id").getAsString();
            JsonObject binding = zone.getAsJsonArray("bindings")
                    .get(0)
                    .getAsJsonObject();
            assertEquals(
                    7,
                    binding.getAsJsonObject("markerArea")
                            .get("firstX")
                            .getAsInt()
            );
            assertEquals(
                    "minecraft:stone_bricks",
                    binding.getAsJsonArray("ignoredMarkerBlockIds")
                            .get(0)
                            .getAsString()
            );
            JsonObject override = binding.getAsJsonArray("containerOverrides")
                    .get(0)
                    .getAsJsonObject();
            assertEquals("ADD", override.get("mode").getAsString());
            assertEquals(
                    "minecraft:oak_planks",
                    override.getAsJsonArray("itemIds")
                            .get(0)
                            .getAsString()
            );

            HttpResponse<String> rules = fixture.send(
                    "/v1/category-rules",
                    "GET",
                    null,
                    true
            );
            assertEquals(200, rules.statusCode());
            assertEquals(
                    "DIRECT_BLOCK_ID",
                    object(rules).getAsJsonArray("rules")
                            .get(0)
                            .getAsJsonObject()
                            .get("id")
                            .getAsString()
            );
            assertEquals(
                    "MARKER_CONTAINER_CONTENTS",
                    object(rules).getAsJsonArray("rules")
                            .get(1)
                            .getAsJsonObject()
                            .get("id")
                            .getAsString()
            );

            String revision = createdBody.get("revision").getAsString();
            HttpResponse<String> updated = fixture.send(
                    "/v1/category-zones/" + zoneId,
                    "PUT",
                    """
                            {"revision":"%s","name":"A2","bindings":[{
                            "markerFirstX":1,"markerFirstY":2,"markerFirstZ":3,
                            "markerSecondX":1,"markerSecondY":2,"markerSecondZ":3,
                            "storageFirstX":4,"storageFirstY":5,"storageFirstZ":6,
                            "storageSecondX":4,"storageSecondY":5,"storageSecondZ":6,
                            "ruleId":"DIRECT_BLOCK_ID",
                            "ignoredMarkerBlockIds":["minecraft:glass"],
                            "containerOverrides":[{
                            "storageX":4,"storageY":5,"storageZ":6,
                            "mode":"REPLACE",
                            "itemIds":["minecraft:quartz_block"]}]}]}
                            """.formatted(revision),
                    true
            );
            assertEquals(200, updated.statusCode());
            assertEquals(
                    "A2",
                    object(updated).getAsJsonArray("categoryZones")
                            .get(0)
                            .getAsJsonObject()
                            .get("name")
                            .getAsString()
            );
        }
    }

    @Test
    void createsAutoFillCategoryZoneFromExpansionPayload() throws Exception {
        try (Fixture fixture = fixture()) {
            HttpResponse<String> response = fixture.send(
                    "/v1/category-zones",
                    "POST",
                    """
                            {"name":"Auto","bindings":[{
                            "markerFirstX":0,"markerFirstY":64,"markerFirstZ":20,
                            "markerSecondX":4,"markerSecondY":64,"markerSecondZ":20,
                            "storageFirstX":0,"storageFirstY":64,"storageFirstZ":18,
                            "storageSecondX":4,"storageSecondY":64,"storageSecondZ":18,
                            "ruleId":"DIRECT_BLOCK_ID",
                            "standingArea":{"firstX":0,"firstY":65,"firstZ":16,
                            "secondX":4,"secondY":65,"secondZ":16}}],
                            "expansion":{
                            "firstX":0,"firstY":63,"firstZ":17,
                            "secondX":24,"secondY":65,"secondZ":21,
                            "direction":"X_POSITIVE","step":10}}
                            """,
                    true
            );

            assertEquals(201, response.statusCode());
            JsonObject zone = object(response).getAsJsonArray("categoryZones")
                    .get(0)
                    .getAsJsonObject();
            assertEquals(3, zone.getAsJsonArray("bindings").size());
            assertEquals(10, zone.getAsJsonArray("bindings").get(1).getAsJsonObject()
                    .getAsJsonObject("standingArea").get("firstX").getAsInt());
            assertEquals(16, zone.getAsJsonArray("bindings").get(1).getAsJsonObject()
                    .getAsJsonObject("standingArea").get("firstZ").getAsInt());
            assertEquals(
                    "X_POSITIVE",
                    zone.getAsJsonObject("expansion")
                            .get("direction")
                            .getAsString()
            );
        }
    }

    @Test
    void exposesInterruptionRecoveryAndRejectsInvalidState() throws Exception {
        try (Fixture fixture = fixture()) {
            HttpResponse<String> created = fixture.send(
                    "/v1/tasks",
                    "POST",
                    """
                            {"name":"Output move","steps":[{
                            "moduleId":"MOVE_TO_COORDINATE",
                            "parameters":{"targetRef":"output:container"}}]}
                            """,
                    true
            );
            assertEquals(201, created.statusCode());
            String taskId = object(created).getAsJsonArray("tasks")
                    .get(0)
                    .getAsJsonObject()
                    .get("id")
                    .getAsString();
            JsonObject run = object(fixture.send(
                    "/v1/tasks/" + taskId + "/runs",
                    "POST",
                    "{}",
                    true
            ));
            String runId = run.get("runId").getAsString();

            HttpResponse<String> retry = fixture.send(
                    "/v1/task-runs/" + runId + "/retry-interruption",
                    "POST",
                    "",
                    true
            );
            HttpResponse<String> skip = fixture.send(
                    "/v1/task-runs/" + runId + "/skip-interruption",
                    "POST",
                    "",
                    true
            );

            assertEquals(409, retry.statusCode());
            assertEquals(409, skip.statusCode());
            assertEquals(
                    "invalid_state",
                    object(retry).getAsJsonObject("error")
                            .get("code")
                            .getAsString()
            );
        }
    }

    private Fixture fixture() throws IOException {
        ConfigStore store = new ConfigStore(directory.resolve("stowling.json"));
        store.loadOrReset();
        V2ControlService service = new V2ControlServiceImpl(
                store,
                new TaskRunManager(new FailedNavigation()),
                List::of
        );
        return new Fixture(service);
    }

    private static String coordinate(
            String name,
            int x,
            int y,
            int z
    ) {
        return """
                {"name":"%s","x":%d,"y":%d,"z":%d}
                """.formatted(name, x, y, z);
    }

    private static String task(String name, List<String> coordinateIds) {
        StringBuilder steps = new StringBuilder();
        for (int index = 0; index < coordinateIds.size(); index++) {
            if (index > 0) {
                steps.append(',');
            }
            steps.append("""
                    {"moduleId":"MOVE_TO_COORDINATE","parameters":{
                    "coordinateId":"%s"}}
                    """.formatted(coordinateIds.get(index)));
        }
        return """
                {"name":"%s","steps":[%s]}
                """.formatted(name, steps);
    }

    private static String categoryZone(String name) {
        return """
                {"name":"%s","bindings":[{
                "markerFirstX":7,"markerFirstY":-34,"markerFirstZ":7,
                "markerSecondX":18,"markerSecondY":-34,"markerSecondZ":7,
                "storageFirstX":7,"storageFirstY":-34,"storageFirstZ":5,
                "storageSecondX":18,"storageSecondY":-34,"storageSecondZ":6,
                "ruleId":"DIRECT_BLOCK_ID",
                "ignoredMarkerBlockIds":["minecraft:stone_bricks"],
                "containerOverrides":[{
                "storageX":8,"storageY":-34,"storageZ":5,
                "mode":"ADD",
                "itemIds":["minecraft:oak_planks"]}]}]}
                """.formatted(name);
    }

    private static JsonObject object(HttpResponse<String> response) {
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static final class Fixture implements AutoCloseable {
        private final HttpClient client = HttpClient.newHttpClient();
        private final V2ControlServer server;
        private final String base;

        private Fixture(V2ControlService service) throws IOException {
            server = new V2ControlServer(
                    new InetSocketAddress("127.0.0.1", 0),
                    service,
                    TOKEN
            );
            server.start();
            base = "http://127.0.0.1:" + server.localAddress().getPort();
        }

        private HttpResponse<String> send(
                String path,
                String method,
                String body,
                boolean authenticated
        ) throws IOException, InterruptedException {
            HttpRequest.Builder request = HttpRequest.newBuilder(
                            URI.create(base + path))
                    .header("Accept", "application/json");
            if (authenticated) {
                request.header("Authorization", "Bearer " + TOKEN);
            }
            if (body != null) {
                request.header("Content-Type", "application/json");
            }
            switch (method) {
                case "GET" -> request.GET();
                case "POST" -> request.POST(bodyPublisher(body));
                case "PUT" -> request.PUT(bodyPublisher(body));
                case "DELETE" -> request.method(
                        "DELETE",
                        bodyPublisher(body)
                );
                case "PATCH" -> request.method(
                        "PATCH",
                        bodyPublisher(body)
                );
                default -> throw new IllegalArgumentException(
                        "unsupported test method: " + method
                );
            }
            return client.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString()
            );
        }

        private static HttpRequest.BodyPublisher bodyPublisher(String body) {
            return body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
        }

        @Override
        public void close() {
            server.close();
        }
    }

    private static final class FailedNavigation implements NavigationPort {
        @Override
        public NavigationStatus start(BlockPosition target) {
            return NavigationStatus.FAILED;
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.FAILED;
        }

        @Override
        public void cancel() {
        }

        @Override
        public StopOutcome requestStop() {
            return StopOutcome.CLEAN;
        }
    }
}
