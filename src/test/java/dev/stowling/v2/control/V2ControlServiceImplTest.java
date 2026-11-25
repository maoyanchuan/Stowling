package dev.stowling.v2.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.config.V2RevisionConflictException;
import dev.stowling.v2.config.V2ValidationException;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CategoryZoneExpansionAxis;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskSchedule;
import dev.stowling.v2.model.TaskStepKind;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.model.TaskTriggerType;
import dev.stowling.v2.runtime.TaskRunManager;
import dev.stowling.v2.runtime.TaskRunState;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V2ControlServiceImplTest {
    @Test
    void batchingDefaultsOnAndExplicitOffSurvivesEditsRunSwitchesAndReload() throws Exception {
        Path path = directory.resolve("classification-batching.json");
        var store = new ConfigStore(path);
        store.loadOrReset();
        var service = new V2ControlServiceImpl(store,
                new TaskRunManager(new FailedNavigation()), List::of);
        var point = createPoint(service, "Cache");
        String pointRef = "coordinate:" + point.id();
        var zone = service.createCategoryZone(new CategoryZoneRequest("Storage",
                List.of(new CategoryBindingRequest(10, 64, 0, 10, 64, 0,
                        11, 64, 0, 11, 64, 0, "DIRECT_BLOCK_ID", List.of(), List.of()))))
                .categoryZones().getFirst();
        var created = service.createAutoClassifyTask(new AutoClassifyTaskRequest(
                "Sort", false, null, null, null, pointRef,
                List.of(new AutoClassifyAreaRequest("category-zone:" + zone.id())),
                TaskPriority.NORMAL, null, null, null, null,
                dev.stowling.v2.model.AutoClassifyDefinition.Kind.CLASSIFICATION, null));
        assertTrue(created.item().sameCategoryBoxBatching());

        var gson = new com.google.gson.Gson();
        var edit = gson.toJsonTree(created.item()).getAsJsonObject();
        edit.addProperty("revision", created.revision());
        edit.addProperty("sameCategoryBoxBatching", false);
        var disabled = service.updateAutoClassifyTask(created.item().id(),
                gson.fromJson(edit, AutoClassifyTaskUpdateRequest.class));
        assertFalse(disabled.item().sameCategoryBoxBatching());

        edit.remove("sameCategoryBoxBatching");
        edit.addProperty("revision", disabled.revision());
        edit.addProperty("name", "Renamed sort");
        var renamed = service.updateAutoClassifyTask(created.item().id(),
                gson.fromJson(edit, AutoClassifyTaskUpdateRequest.class));
        assertFalse(renamed.item().sameCategoryBoxBatching());
        var started = service.startAutoClassifyTask(created.item().id(),
                new RevisionRequest(renamed.revision()));
        assertFalse(started.item().sameCategoryBoxBatching());
        var stopped = service.stopAutoClassifyTask(created.item().id(),
                new RevisionRequest(started.revision()));
        assertFalse(stopped.item().sameCategoryBoxBatching());
        var reopened = new ConfigStore(path).loadOrReset().config();
        assertFalse(reopened.autoClassifyTasks().getFirst().sameCategoryBoxBatching());

        var legacy = com.google.gson.JsonParser.parseString(
                java.nio.file.Files.readString(path)).getAsJsonObject();
        legacy.getAsJsonArray("autoClassifyTasks").get(0).getAsJsonObject()
                .remove("sameCategoryBoxBatching");
        java.nio.file.Files.writeString(path, gson.toJson(legacy));
        assertTrue(new ConfigStore(path).loadOrReset().config().autoClassifyTasks()
                .getFirst().sameCategoryBoxBatching());
    }

    @Test
    void retainsDisabledRefillDraftAndTaskPriorityAcrossReloads() {
        Path path = directory.resolve("auto-eat-refill.json");
        var store = new ConfigStore(path);
        store.loadOrReset();
        var service = new V2ControlServiceImpl(store,
                new TaskRunManager(new FailedNavigation()), List::of);
        var chest = createPoint(service, "Food chest");
        var settings = new AutoEatTask.Settings("Meal", "minecraft:bread", chest.id(), 15, 3,
                false, TaskPriority.NORMAL, true, 17, false);
        var created = service.saveAutoEatTask(null, service.autoEatTasks().revision(), settings);
        assertEquals(settings, created.items().getFirst().settings());
        assertEquals(TaskPriority.NORMAL, service.tasks().tasks().getFirst().priority());
        assertEquals(TaskPriority.NORMAL, service.taskTriggers().triggers().getFirst().priority());

        var listening = service.setAutoEatListening(created.taskId(),
                new RevisionRequest(created.revision()), true);
        var disabled = new AutoEatTask.Settings("Meal", "minecraft:bread", chest.id(), 15, 3,
                true, TaskPriority.BACKGROUND, false, 17, false);
        service.saveAutoEatTask(created.taskId(), listening.revision(), disabled);

        var reopened = new ConfigStore(path);
        reopened.loadOrReset();
        var restored = new V2ControlServiceImpl(reopened,
                new TaskRunManager(new FailedNavigation()), List::of);
        var saved = restored.autoEatTasks();
        assertEquals(disabled, saved.items().getFirst().settings());
        assertEquals(TaskPriority.BACKGROUND, restored.tasks().tasks().getFirst().priority());
        var trigger = restored.taskTriggers().triggers().getFirst();
        assertEquals(TaskPriority.BACKGROUND, trigger.priority());
        assertEquals("minecraft:bread", trigger.requiredProtectedFood());
        assertTrue(trigger.enabled());

        var enabled = new AutoEatTask.Settings("Meal", "minecraft:bread", chest.id(), 15, 3,
                true, TaskPriority.BACKGROUND, true, 17, false);
        var updated = restored.saveAutoEatTask(created.taskId(), saved.revision(), enabled);
        assertEquals(enabled, updated.items().getFirst().settings());
        assertNull(restored.taskTriggers().triggers().getFirst().requiredProtectedFood());
        var flow = restored.tasks().tasks().getFirst().steps().getFirst().steps();
        assertEquals(ConfigValidator.MOVE_TO_COORDINATE, flow.getFirst().moduleId());
        assertEquals("17", flow.get(1).parameters().get(ConfigValidator.REFILL_COUNT));
        assertEquals("true", flow.get(1).parameters().get(ConfigValidator.RESERVE_FOOD));
    }

    @Test
    void readsLegacyMealsWithTheirExistingPriorityAndDefaultRefillOptions() {
        var settings = new AutoEatTask.Settings("Meal", "minecraft:bread", null, 18, 1, false);
        var generated = settings.task(UUID.randomUUID(), "Meal");
        var legacy = new dev.stowling.v2.model.TaskDefinition(generated.id(), "Meal",
                TaskPriority.NORMAL, generated.steps());
        var restored = AutoEatTask.from(legacy, settings.trigger(legacy, false)).settings();
        assertEquals(TaskPriority.NORMAL, restored.priority());
        assertFalse(restored.autoRefill());
        assertEquals(64, restored.refillCount());
        assertTrue(restored.preferInventory());
    }

    @Test
    void savesIndependentPickupAndClassificationAndPersistsTheChosenSuccessor() {
        var path = directory.resolve("independent-storage-tasks.json");
        var store = new ConfigStore(path);
        store.loadOrReset();
        var service = new V2ControlServiceImpl(store,
                new TaskRunManager(new FailedNavigation()), List::of);
        var point = createPoint(service, "Pickup");
        String pointRef = "coordinate:" + point.id();
        var zone = service.createCategoryZone(new CategoryZoneRequest("Sorted storage",
                List.of(new CategoryBindingRequest(10, 64, 0, 10, 64, 0,
                        11, 64, 0, 11, 64, 0, "DIRECT_BLOCK_ID", List.of(), List.of()))))
                .categoryZones().getFirst();
        var kind = dev.stowling.v2.model.AutoClassifyDefinition.Kind.CLASSIFICATION;
        var classification = service.createAutoClassifyTask(new AutoClassifyTaskRequest(
                "Sort", false, null, null, null, pointRef,
                List.of(new AutoClassifyAreaRequest("category-zone:" + zone.id())),
                TaskPriority.NORMAL, null, null, null, null, kind, null));
        assertNull(classification.item().detection());
        assertTrue(classification.item().triggerIds().isEmpty());
        String next = "task:" + classification.item().id();
        var pickup = service.createAutoClassifyTask(new AutoClassifyTaskRequest(
                "Pickup", false, null, null, new AutoClassifyDetectionRequest(pointRef, null, 6),
                pointRef, List.of(), TaskPriority.NORMAL, null, null, null, null,
                dev.stowling.v2.model.AutoClassifyDefinition.Kind.PICKUP, next, 45));
        assertEquals(next, pickup.item().followUpTaskRef());
        assertTrue(pickup.item().classificationAreas().isEmpty());
        assertEquals(6, pickup.item().detection().intervalSeconds());
        assertEquals(45, pickup.item().followUpDelaySeconds());

        var listening = service.startAutoClassifyTask(pickup.item().id(),
                new RevisionRequest(pickup.revision()));
        assertEquals(1, listening.item().triggerIds().size());
        assertTrue(service.taskRuns().queue().isEmpty());
        var reopened = new ConfigStore(path).loadOrReset().config();
        var followUp = reopened.tasks().stream().filter(task -> task.id().equals(pickup.item().id()))
                .findFirst().orElseThrow().steps().getLast();
        assertEquals(TaskStepKind.CREATE_TASK, followUp.kind());
        assertEquals(next, followUp.parameters().get(ConfigValidator.TASK_REF));
        assertEquals("45", followUp.parameters().get(ConfigValidator.CREATE_DELAY_SECONDS));
        assertEquals(120, reopened.triggers().getFirst().intervalTicks());
        assertEquals(6, listening.item().detection().intervalSeconds());
        assertTrue(listening.item().followUpEnabled());

        var disabled = service.updateAutoClassifyTask(pickup.item().id(),
                new AutoClassifyTaskUpdateRequest(listening.revision(), null, null, null, null,
                        new AutoClassifyDetectionRequest(pointRef, null, 6), pointRef, List.of(),
                        TaskPriority.NORMAL, null, null, null, null, null, null, null, false));
        assertFalse(disabled.item().followUpEnabled());
        assertEquals(next, disabled.item().followUpTaskRef());
        assertEquals(45, disabled.item().followUpDelaySeconds());
        var disabledConfig = new ConfigStore(path).loadOrReset().config();
        assertFalse(disabledConfig.autoClassifyTasks().stream()
                .filter(item -> item.id().equals(pickup.item().id()))
                .findFirst().orElseThrow().followUpEnabled());
        assertTrue(disabledConfig.tasks().stream()
                .filter(task -> task.id().equals(pickup.item().id()))
                .findFirst().orElseThrow().steps().stream()
                .noneMatch(step -> step.kind() == TaskStepKind.CREATE_TASK));

        var stopped = service.stopAutoClassifyTask(pickup.item().id(),
                new RevisionRequest(disabled.revision()));
        var restarted = service.startAutoClassifyTask(pickup.item().id(),
                new RevisionRequest(stopped.revision()));
        assertFalse(restarted.item().followUpEnabled());
        assertEquals(next, restarted.item().followUpTaskRef());

        var resumed = service.updateAutoClassifyTask(pickup.item().id(),
                new AutoClassifyTaskUpdateRequest(restarted.revision(), null, null, null, null,
                        new AutoClassifyDetectionRequest(pointRef, null, 6), pointRef, List.of(),
                        TaskPriority.NORMAL, null, null, null, null, null, null, null, true));
        assertTrue(resumed.item().followUpEnabled());
        assertEquals(next, resumed.item().followUpTaskRef());
        assertEquals(45, resumed.item().followUpDelaySeconds());
        var resumedConfig = new ConfigStore(path).loadOrReset().config();
        assertEquals(TaskStepKind.CREATE_TASK, resumedConfig.tasks().stream()
                .filter(task -> task.id().equals(pickup.item().id()))
                .findFirst().orElseThrow().steps().getLast().kind());
    }

    @Test
    void pickupFollowUpDefaultsOffAndCanBeEnabledBeforeSelectingASuccessor() {
        var path = directory.resolve("pickup-follow-up-switch.json");
        var store = new ConfigStore(path);
        store.loadOrReset();
        var service = new V2ControlServiceImpl(store,
                new TaskRunManager(new FailedNavigation()), List::of);
        var point = createPoint(service, "Pickup");
        String pointRef = "coordinate:" + point.id();
        var created = service.createAutoClassifyTask(new AutoClassifyTaskRequest(
                "Pickup", false, null, null, new AutoClassifyDetectionRequest(pointRef, null),
                pointRef, List.of(), TaskPriority.NORMAL, null, null, null, null,
                dev.stowling.v2.model.AutoClassifyDefinition.Kind.PICKUP, null));
        assertFalse(created.item().followUpEnabled());

        var enabled = service.updateAutoClassifyTask(created.item().id(),
                new AutoClassifyTaskUpdateRequest(created.revision(), null, null, null, null,
                        new AutoClassifyDetectionRequest(pointRef, null), pointRef, List.of(),
                        TaskPriority.NORMAL, null, null, null, null, null, null, null, true));
        assertTrue(enabled.item().followUpEnabled());
        assertNull(enabled.item().followUpTaskRef());
        assertTrue(new ConfigStore(path).loadOrReset().config()
                .autoClassifyTasks().getFirst().followUpEnabled());
    }

    @Test
    void persistsMixedRootOrderAndMovesCoordinatesBetweenFolders() {
        var path = directory.resolve("bookmarks.json");
        var store = new ConfigStore(path);
        store.loadOrReset();
        var service = new V2ControlServiceImpl(store,
                new TaskRunManager(new FailedNavigation()), List::of);
        var point = createPoint(service, "Pickup");
        var region = service.createRegion(new RegionRequest("Cache", 0, 64, 0, 2, 64, 2))
                .regions().getFirst();
        var folderId = UUID.randomUUID();
        String pointRef = "coordinate:" + point.id();
        String regionRef = "region:" + region.id();
        String folderRef = "folder:" + folderId;
        var initial = service.editCoordinateDirectory(service.coordinateDirectory().revision(),
                catalog -> catalog.editFolder(folderId, "Collection"));

        var sorted = service.placeCoordinateBookmark(initial.revision(), folderRef, null, pointRef);
        assertEquals(List.of(folderRef, pointRef, regionRef), sorted.catalog().order());

        var moved = service.placeCoordinateBookmark(sorted.revision(), regionRef, folderId, null);
        var grouped = service.placeCoordinateBookmark(
                moved.revision(), pointRef, folderId, regionRef);
        assertEquals(Map.of(pointRef, folderId, regionRef, folderId), grouped.catalog().placements());
        assertEquals(List.of(folderRef, pointRef, regionRef), grouped.catalog().order());
        assertThrows(IllegalArgumentException.class, () -> service.placeCoordinateBookmark(
                grouped.revision(), folderRef, folderId, regionRef));
        assertThrows(IllegalArgumentException.class, () -> service.placeCoordinateBookmark(
                grouped.revision(), regionRef, null, pointRef));

        var restored = service.placeCoordinateBookmark(grouped.revision(), regionRef, null,
                folderRef);
        var saved = new ConfigStore(path).loadOrReset().config().coordinateCatalog();
        assertEquals(List.of(regionRef, folderRef, pointRef), saved.order());
        assertEquals(Map.of(pointRef, folderId), saved.placements());
        var deleted = service.deleteRegion(region.id(), new RevisionRequest(restored.revision()));
        assertEquals(deleted.revision(), service.coordinateDirectory().revision());
        assertEquals(List.of(folderRef, pointRef), service.coordinateDirectory().catalog().order());
    }

    @Test
    void keepsWarehouseAndTaskReferencesWhenRemovingTheirFolder() {
        var service = service();
        var point = createPoint(service, "Cache chest");
        var pointRef = "coordinate:" + point.id();
        var folderId = UUID.randomUUID();
        var warehouseId = UUID.randomUUID();
        var warehouseRef = "warehouse:" + warehouseId;
        var directory = service.editCoordinateDirectory(service.coordinateDirectory().revision(),
                catalog -> catalog.editFolder(folderId, "Collection")
                        .editWarehouse(warehouseId, "Buffer", List.of(pointRef))
                        .move(pointRef, folderId).move(warehouseRef, folderId));

        var request = new AutoClassifyTaskRequest("Collect", true, true, false,
                new AutoClassifyDetectionRequest(pointRef, null), warehouseRef,
                List.of(), TaskPriority.NORMAL);
        var created = service.createAutoClassifyTask(request);
        assertNull(created.item().detection().pickupRadius());
        assertEquals(warehouseRef, created.item().cleanupPolicy().destinationRef());
        assertThrows(V2RevisionConflictException.class,
                () -> service.editCoordinateDirectory(directory.revision(),
                        catalog -> catalog.editFolder(folderId, null)));

        var updated = service.editCoordinateDirectory(created.revision(),
                catalog -> catalog.editFolder(folderId, null));
        assertTrue(updated.catalog().placements().isEmpty());
        assertEquals(List.of(pointRef), updated.catalog().warehouses().getFirst().members());
        assertTrue(updated.references().get(pointRef).contains("Buffer"));
        assertTrue(updated.references().get(warehouseRef).contains("Collect"));
        assertThrows(V2ValidationException.class,
                () -> service.editCoordinateDirectory(updated.revision(),
                        catalog -> catalog.editWarehouse(warehouseId, null, List.of())));
        assertThrows(CoordinateInUseException.class, () -> service.deleteCoordinate(point.id(),
                new RevisionRequest(updated.revision())));
    }

    @Test
    void startsTheRefreshScheduleOnlyAfterEnablingAndPreservesItsAnchorOnEdits() {
        Path path = directory.resolve("refresh-schedule.json");
        ConfigStore store = new ConfigStore(path);
        store.loadOrReset();
        var service = new V2ControlServiceImpl(store,
                new TaskRunManager(new FailedNavigation()), List::of);
        var region = service.createRegion(new RegionRequest("Stock", 0, 64, 0, 3, 64, 3))
                .regions().getFirst();
        var steps = List.of(new TaskStepRequest(null, "REFRESH_CONTAINER_CACHE",
                Map.of("targetRef", "region:" + region.id())));
        var hourly = new TaskSchedule(TaskSchedule.Mode.INTERVAL, null, null, 60, null);
        var created = service.createTask(new TaskRequest("Refresh", TaskPriority.NORMAL,
                false, steps, null, null, false, false, null, null, hourly));
        UUID id = created.taskId();
        assertNull(created.schedules().get(id).anchorEpochSecond());
        assertTrue(created.enabledSchedules().isEmpty());
        assertTrue(service.taskRuns().triggers().isEmpty());

        var started = service.setRefreshTaskRunning(id, new RevisionRequest(created.revision()),
                true);
        assertTrue(started.scheduleEnabled());
        assertNull(started.runState());
        assertNotNull(started.nextAt());
        TaskSchedule persisted = service.tasks().schedules().get(id);
        assertNotNull(persisted.anchorEpochSecond());

        var reopened = new ConfigStore(path);
        reopened.loadOrReset();
        var restarted = new V2ControlServiceImpl(reopened,
                new TaskRunManager(new FailedNavigation()), List::of);
        assertEquals(persisted, restarted.tasks().schedules().get(id));
        var renamed = restarted.updateTask(id, new TaskUpdateRequest(
                restarted.tasks().revision(), "Renamed", steps));
        assertEquals(persisted, renamed.schedules().get(id));
        assertTrue(renamed.enabledSchedules().contains(id));

        var paused = restarted.setRefreshTaskRunning(id, new RevisionRequest(renamed.revision()),
                false);
        assertFalse(paused.scheduleEnabled());
        assertNull(paused.nextAt());
        assertTrue(restarted.taskRuns().triggers().isEmpty());
        assertEquals(persisted, restarted.tasks().schedules().get(id));

        var manual = new TaskSchedule(TaskSchedule.Mode.MANUAL, null, null, null, null);
        var updated = restarted.updateTask(id, new TaskUpdateRequest(paused.revision(),
                "Renamed", TaskPriority.NORMAL, false, steps, null, null, false, false,
                null, null, manual));
        assertTrue(updated.schedules().isEmpty());
        assertTrue(restarted.taskRuns().triggers().isEmpty());
    }

    @Test
    void startsManualRefreshOnceAndRequestsSafeStop() {
        var service = service();
        var region = service.createRegion(new RegionRequest("Stock", 0, 64, 0, 3, 64, 3))
                .regions().getFirst();
        var steps = List.of(new TaskStepRequest(null, "REFRESH_CONTAINER_CACHE",
                Map.of("targetRef", "region:" + region.id())));
        var created = service.createTask(new TaskRequest("Manual refresh", steps));
        UUID id = created.taskId();
        var request = new RevisionRequest(created.revision());

        var started = service.setRefreshTaskRunning(id, request, true);
        assertEquals(TaskRunState.RUNNING, started.runState());
        assertFalse(started.scheduleEnabled());
        assertNull(started.nextAt());
        service.setRefreshTaskRunning(id, request, true);
        assertEquals(1, service.taskRuns().runs().stream()
                .filter(run -> run.taskId().equals(id)).count());

        var stopped = service.setRefreshTaskRunning(id, request, false);
        assertEquals(TaskRunState.INTERRUPTING, stopped.runState());
        assertTrue(service.taskRuns().queue().isEmpty());
    }

    @Test
    void savesAndReloadsPausedAutoEatWithChestAndUpdatesItToBackpackOnly() {
        Path path = directory.resolve("auto-eat.json");
        ConfigStore store = new ConfigStore(path);
        store.loadOrReset();
        var service = new V2ControlServiceImpl(store,
                new TaskRunManager(new FailedNavigation()), List::of);
        var chest = createPoint(service, "Food chest");
        var paused = new AutoEatTask.Settings("Meal", "minecraft:bread", chest.id(), 18, 2, false);
        var created = service.saveAutoEatTask(null, service.autoEatTasks().revision(), paused);

        var reopened = new ConfigStore(path);
        reopened.loadOrReset();
        var restoredService = new V2ControlServiceImpl(reopened,
                new TaskRunManager(new FailedNavigation()), List::of);
        var restored = restoredService.autoEatTasks();
        assertEquals(List.of(new AutoEatTask(created.taskId(), paused)), restored.items());
        assertTrue(restoredService.taskRuns().triggers().isEmpty());

        var carry = new AutoEatTask.Settings("Meal", "minecraft:bread", null, 18, 2, false);
        var updated = restoredService.saveAutoEatTask(created.taskId(), restored.revision(), carry);
        assertEquals(List.of(new AutoEatTask(created.taskId(), carry)), updated.items());
        assertEquals("minecraft:bread",
                restoredService.taskTriggers().triggers().getFirst().requiredProtectedFood());
        var deleted = restoredService.deleteAutoEatTask(
                created.taskId(), new RevisionRequest(updated.revision()));
        assertTrue(deleted.items().isEmpty());
        assertTrue(restoredService.taskTriggers().triggers().isEmpty());
    }

    @Test
    void onlyTheListeningOperationChangesAutoEatState() {
        V2ControlService service = service();
        var settings = new AutoEatTask.Settings("Meal", "minecraft:bread", null, 18, 2, true);
        var created = service.saveAutoEatTask(null, service.autoEatTasks().revision(), settings);
        var id = created.taskId();
        assertFalse(created.items().getFirst().settings().enabled());
        assertTrue(service.taskRuns().triggers().isEmpty());

        var paused = service.saveAutoEatTask(id, created.revision(), settings);
        assertFalse(paused.items().getFirst().settings().enabled());
        var started = service.setAutoEatListening(id, new RevisionRequest(paused.revision()), true);
        assertTrue(started.items().getFirst().settings().enabled());
        assertEquals(1, service.taskRuns().triggers().size());

        var edit = new AutoEatTask.Settings("Meal", "minecraft:bread", null, 17, 2, false);
        var updated = service.saveAutoEatTask(id, started.revision(), edit);
        assertTrue(updated.items().getFirst().settings().enabled());
        assertEquals(17, updated.items().getFirst().settings().foodLevelThreshold());
        assertEquals(1, service.taskRuns().triggers().size());

        var stopped = service.setAutoEatListening(id, new RevisionRequest(updated.revision()),
                false);
        assertFalse(stopped.items().getFirst().settings().enabled());
        assertTrue(service.taskRuns().triggers().isEmpty());
    }

    @Test
    void cleanupDestinationSurvivesLegacyTaskEditsAndProtectsItsCoordinate() {
        V2ControlService service = service();
        CoordinatePoint source = createPoint(service, "Source");
        CoordinatePoint destination = createPoint(service, "Delivery");
        var policy = new dev.stowling.v2.model.TaskCleanupPolicy(
                "coordinate:" + destination.id(), dev.stowling.v2.model.TaskResumeMode.CONTINUE);
        TaskRequest original = task("Gather", source.id().toString());
        TaskListResponse created = service.createTask(new TaskRequest(original.name(),
                original.priority(), original.repeatWhenIdle(), original.steps(),
                original.interruptionMode(), original.interruptionTaskRef(),
                original.defaultInterruptionFlow(), original.requeueAfterInterruption(),
                original.folderId(), policy));
        var saved = created.tasks().getFirst();
        assertEquals(policy, saved.cleanupPolicy());
        TaskListResponse updated = service.updateTask(saved.id(), new TaskUpdateRequest(
                created.revision(), original.name(), original.priority(), original.repeatWhenIdle(),
                original.steps(), original.interruptionMode(), original.interruptionTaskRef(),
                original.defaultInterruptionFlow(), original.requeueAfterInterruption(),
                original.folderId()));
        assertEquals(policy, updated.tasks().getFirst().cleanupPolicy());
        assertThrows(V2ValidationException.class, () -> service.deleteCoordinate(destination.id(),
                new RevisionRequest(updated.revision())));
    }

    @TempDir
    Path directory;

    @Test
    void createTaskResponseIncludesExactCreatedTaskId() {
        V2ControlService service = service();
        TaskListResponse created = service.createTask(new TaskRequest(
                "Created",
                List.of(moveOutputStep())
        ));

        assertNotNull(created.taskId());
        assertEquals("Created", service.task(created.taskId()).name());
        assertNull(service.tasks().taskId());
    }

    @Test
    void namesUnnamedTasksByTypeAndKeepsTheAssignedNameOnUpdate() {
        V2ControlService service = service();
        var steps = List.of(moveOutputStep());

        var first = service.createTask(new TaskRequest(null, steps));
        var second = service.createTask(new TaskRequest(null, steps));
        assertEquals("移动到坐标", service.task(first.taskId()).name());
        assertEquals("移动到坐标 2", service.task(second.taskId()).name());

        service.updateTask(second.taskId(), new TaskUpdateRequest(second.revision(), null, steps));
        assertEquals("移动到坐标 2", service.task(second.taskId()).name());
    }

    @Test
    void assignsDifferentNamesToUnnamedAutoEatTasksAndTheirListeners() {
        V2ControlService service = service();
        var settings = new AutoEatTask.Settings(null, "minecraft:bread", null, 18, 2, false);
        var first = service.saveAutoEatTask(null, service.autoEatTasks().revision(), settings);
        var second = service.saveAutoEatTask(null, first.revision(), settings);

        assertEquals(List.of("自动进食", "自动进食 2"),
                second.items().stream().map(item -> item.settings().name()).toList());
        assertEquals(List.of("自动进食", "自动进食 2"),
                service.taskTriggers().triggers().stream().map(TaskTriggerDefinition::name)
                        .toList());
    }

    @Test
    void blocksDeletingCoordinateReferencedByTaskAndListsReferences() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Home");
        TaskListResponse tasks = service.createTask(task(
                "Go home",
                point.id().toString()
        ));

        CoordinateInUseException exception = assertThrows(
                CoordinateInUseException.class,
                () -> service.deleteCoordinate(
                        point.id(),
                        new RevisionRequest(tasks.revision())
                )
        );

        assertEquals(List.of("Go home"), exception.taskNames());
    }

    @Test
    void renamingCoordinateKeepsTaskReferencingTheSamePoint() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Home");
        TaskListResponse tasks = service.createTask(task(
                "Go home",
                point.id().toString()
        ));

        service.updateCoordinate(
                point.id(),
                new CoordinateUpdateRequest(
                        tasks.revision(),
                        "Base",
                        30,
                        70,
                        -20
                )
        );

        CoordinatePoint renamed = service.coordinate(point.id());
        assertEquals("Base", renamed.name());
        assertEquals(
                "coordinate:" + point.id(),
                service.tasks().tasks().getFirst()
                        .steps().getFirst()
                        .parameters()
                        .get(ConfigValidator.TARGET_REF)
        );
    }

    @Test
    void rejectsUnknownModuleAndUnexpectedTaskParameter() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Home");

        assertThrows(
                V2ValidationException.class,
                () -> service.createTask(new TaskRequest(
                        "Unknown",
                        List.of(new TaskStepRequest(
                                null,
                                "UNKNOWN",
                                Map.of(
                                        ConfigValidator.COORDINATE_ID,
                                        point.id().toString()
                                )
                        ))
                ))
        );
        assertThrows(
                V2ValidationException.class,
                () -> service.createTask(new TaskRequest(
                        "Extra",
                        List.of(new TaskStepRequest(
                                null,
                                ConfigValidator.MOVE_TO_COORDINATE,
                                Map.of(
                                        ConfigValidator.COORDINATE_ID,
                                        point.id().toString(),
                                        "radius",
                                        "3"
                                )
                        ))
                ))
        );
    }

    @Test
    void rejectsDuplicateCoordinateAndTaskNames() {
        V2ControlService service = service();
        createPoint(service, "Home");
        assertThrows(
                V2ValidationException.class,
                () -> service.createCoordinate(
                        new CoordinateRequest(" home ", 1, 64, 1)
                )
        );

        V2ControlService taskService = service();
        CoordinatePoint point = createPoint(taskService, "Home");
        taskService.createTask(task("Travel", point.id().toString()));
        assertThrows(
                V2ValidationException.class,
                () -> taskService.createTask(task(
                        " travel ",
                        point.id().toString()
                ))
        );
    }

    @Test
    void createsDraftAutoClassifyAndHidesDerivedTasks() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Drops");
        RegionDefinition region = service.createRegion(new RegionRequest(
                "Cache",
                0,
                64,
                0,
                8,
                70,
                8
        )).regions().getFirst();
        CategoryZoneDefinition zone = service.createCategoryZone(
                new CategoryZoneRequest(
                        "Classification",
                        List.of(new CategoryBindingRequest(
                                20,
                                64,
                                20,
                                24,
                                64,
                                24,
                                20,
                                64,
                                18,
                                24,
                                64,
                                18,
                                "DIRECT_BLOCK_ID",
                                List.of(),
                                List.of()
                        ), new CategoryBindingRequest(
                                30,
                                64,
                                20,
                                34,
                                64,
                                20,
                                30,
                                64,
                                18,
                                34,
                                64,
                                18,
                                "CONTAINER_CONTENTS",
                                List.of(),
                                List.of()
                        ))
                )
        ).categoryZones().getFirst();
        CategoryZoneDefinition containerZone = service.createCategoryZone(
                new CategoryZoneRequest(
                        "Container classification",
                        List.of(new CategoryBindingRequest(
                                40,
                                64,
                                20,
                                44,
                                64,
                                20,
                                40,
                                64,
                                18,
                                44,
                                64,
                                18,
                                "CONTAINER_CONTENTS",
                                List.of(),
                                List.of()
                        ))
                )
        ).categoryZones().getFirst();

        var overflowId = UUID.randomUUID();
        service.editCoordinateDirectory(service.coordinateDirectory().revision(), catalog ->
                catalog.editWarehouse(overflowId, "Overflow", List.of("coordinate:" + point.id())));
        AutoClassifyTaskResponse created = service.createAutoClassifyTask(
                new AutoClassifyTaskRequest(
                        "自动分类",
                        false,
                        true,
                        true,
                        new AutoClassifyDetectionRequest(
                                "coordinate:" + point.id(),
                                8
                        ),
                        "region:" + region.id(),
                        List.of(
                                new AutoClassifyAreaRequest("category-zone:" + zone.id()),
                                new AutoClassifyAreaRequest("category-zone:" + containerZone.id())
                        ),
                        TaskPriority.NORMAL,
                        true, null, "warehouse:" + overflowId
                )
        );

        assertEquals(0, service.tasks().tasks().size());
        assertEquals(1, service.autoClassifyTasks().items().size());
        assertEquals(false, created.item().enabled());
        assertTrue(created.item().collectionEnabled());
        assertTrue(created.item().classificationEnabled());
        assertTrue(created.item().overflowEnabled());
        assertEquals(2, created.item().classificationAreas().size());
        assertTrue(created.item().triggerIds().isEmpty());

        AutoClassifyTaskResponse collision = service.createAutoClassifyTask(
                new AutoClassifyTaskRequest(
                        "自动分类 分类",
                        false,
                        null,
                        null,
                        new AutoClassifyDetectionRequest(
                                "coordinate:" + point.id(),
                                8
                        ),
                        "region:" + region.id(),
                        List.of(new AutoClassifyAreaRequest("category-zone:" + zone.id())),
                        TaskPriority.NORMAL
                )
        );
        assertTrue(!collision.item().name().equals("自动分类 分类"));
        assertTrue(collision.item().collectionEnabled());
        assertTrue(collision.item().classificationEnabled());
        assertFalse(collision.item().overflowEnabled());
        assertEquals(2, service.autoClassifyTasks().items().size());

        TaskListResponse manual = service.createTask(task(
                "普通任务",
                point.id().toString()
        ));
        TaskFolderListResponse temporaryFolder = service.createTaskFolder(
                new TaskFolderRequest("临时")
        );
        TaskFolderListResponse folderRemoved = service.deleteTaskFolder(
                temporaryFolder.folders().getLast().id(),
                new RevisionRequest(temporaryFolder.revision())
        );
        TaskListResponse manualRemoved = service.deleteTask(
                manual.taskId(),
                new RevisionRequest(folderRemoved.revision())
        );
        assertEquals(2, service.autoClassifyTasks().items().size());

        AutoClassifyTaskResponse started = service.startAutoClassifyTask(
                created.item().id(),
                new RevisionRequest(manualRemoved.revision())
        );
        assertTrue(started.item().enabled());
        assertTrue(started.item().overflowEnabled());
        assertEquals(1, started.item().triggerIds().size());
        assertEquals(0, service.tasks().tasks().size());
        assertThrows(
                IllegalStateException.class,
                () -> service.updateAutoClassifyTask(
                        created.item().id(),
                        new AutoClassifyTaskUpdateRequest(
                                started.revision(),
                                "改名",
                                true,
                                false,
                                true,
                                new AutoClassifyDetectionRequest(
                                        "coordinate:" + point.id(),
                                        8
                                ),
                                "region:" + region.id(),
                                List.of(new AutoClassifyAreaRequest("category-zone:" + zone.id())),
                                TaskPriority.NORMAL
                        )
                )
        );
        assertThrows(
                IllegalStateException.class,
                () -> service.deleteAutoClassifyTask(
                        created.item().id(),
                        new RevisionRequest(started.revision())
                )
        );
    }

    @Test
    void createsAutoFillCategoryZoneFromMultipleTemplates() {
        V2ControlService service = service();
        CategoryZoneDefinition zone = service.createCategoryZone(
                new CategoryZoneRequest(
                        "Auto fill",
                        List.of(new CategoryBindingRequest(
                                0,
                                64,
                                20,
                                4,
                                64,
                                20,
                                0,
                                64,
                                18,
                                4,
                                64,
                                18,
                                "DIRECT_BLOCK_ID",
                                List.of(),
                                List.of()
                        ), new CategoryBindingRequest(
                                0,
                                64,
                                24,
                                4,
                                64,
                                24,
                                0,
                                64,
                                22,
                                4,
                                64,
                                22,
                                "DIRECT_BLOCK_ID",
                                List.of(),
                                List.of()
                        )),
                        new CategoryZoneExpansionRequest(
                                0,
                                63,
                                17,
                                14,
                                65,
                                25,
                                "X_POSITIVE",
                                10
                        )
                )
        ).categoryZones().getFirst();

        assertEquals(4, zone.bindings().size());
        assertEquals(2, zone.expansion().templateCount());
        assertEquals(
                CategoryZoneExpansionAxis.X_POSITIVE,
                zone.expansion().direction()
        );
        assertEquals(10, zone.expansion().step());
        assertEquals(10, zone.bindings().get(3).markerArea().minX());
    }

    @Test
    void rejectsAStaleCoordinateRevision() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Home");
        CoordinateListResponse current = service.coordinates();

        assertThrows(
                V2RevisionConflictException.class,
                () -> service.updateCoordinate(
                        point.id(),
                        new CoordinateUpdateRequest(
                                "stale",
                                "Base",
                                1,
                                2,
                                3
                        )
                )
        );
        assertEquals(current.coordinates().getFirst(), service.coordinate(point.id()));
    }

    @Test
    void supportsRegionCrud() {
        V2ControlService service = service();
        RegionListResponse created = service.createRegion(new RegionRequest(
                "Farm",
                0,
                64,
                0,
                10,
                70,
                10
        ));
        var region = created.regions().getFirst();
        assertEquals("Farm", region.name());

        RegionListResponse updated = service.updateRegion(
                region.id(),
                new RegionUpdateRequest(
                        created.revision(),
                        "Field",
                        -5,
                        60,
                        -5,
                        15,
                        72,
                        15
                )
        );
        assertEquals("Field", updated.regions().getFirst().name());
        assertEquals(15, updated.regions().getFirst().secondZ());

        RegionListResponse deleted = service.deleteRegion(
                region.id(),
                new RevisionRequest(updated.revision())
        );
        assertEquals(List.of(), deleted.regions());
    }

    @Test
    void storesTaskPriorityAndBackgroundRepeatMode() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Home");
        TaskListResponse created = service.createTask(new TaskRequest(
                "Classify",
                TaskPriority.BACKGROUND,
                true,
                List.of(new TaskStepRequest(
                        null,
                        ConfigValidator.MOVE_TO_COORDINATE,
                        Map.of(ConfigValidator.COORDINATE_ID, point.id().toString())
                ))
        ));
        var task = created.tasks().getFirst();

        assertEquals(TaskPriority.BACKGROUND, task.priority());
        assertEquals(true, task.repeatWhenIdle());

        TaskListResponse updated = service.updateTask(
                task.id(),
                new TaskUpdateRequest(
                        created.revision(),
                        "Classify",
                        TaskPriority.NORMAL,
                        false,
                        List.of(new TaskStepRequest(
                                null,
                                ConfigValidator.MOVE_TO_COORDINATE,
                                Map.of(
                                        ConfigValidator.COORDINATE_ID,
                                        point.id().toString()
                                )
                        ))
                )
        );
        assertEquals(TaskPriority.NORMAL, updated.tasks().getFirst().priority());
        assertEquals(false, updated.tasks().getFirst().repeatWhenIdle());
    }

    @Test
    void storesIndependentDroppedItemTriggerAndExposesItsIdleStatus() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Drops");
        CoordinatePoint taskPoint = createPoint(service, "Task target");
        TaskListResponse tasks = service.createTask(new TaskRequest(
                "Pickup",
                List.of(new TaskStepRequest(
                        null,
                        ConfigValidator.MOVE_TO_COORDINATE,
                        Map.of(
                                ConfigValidator.COORDINATE_ID,
                                taskPoint.id().toString()
                        )
                ))
        ));
        TaskTriggerListResponse created = service.createTaskTrigger(
                new TaskTriggerRequest(
                        "Pickup trigger",
                        TaskTriggerType.DROPPED_ITEMS,
                        tasks.tasks().getFirst().id(),
                        TaskPriority.NORMAL,
                        20,
                        100,
                        point.id(),
                        "minecraft:oak_log",
                        null,
                        null
                )
        );

        TaskTriggerDefinition trigger = created.triggers().getFirst();
        assertEquals("minecraft:oak_log", trigger.itemFilter());
        assertEquals(TaskPriority.NORMAL, trigger.priority());
        assertEquals(1, service.taskRuns().triggers().size());
        assertEquals(
                "IDLE",
                service.taskRuns().triggers().getFirst().state().name()
        );
        CoordinateInUseException exception = assertThrows(
                CoordinateInUseException.class,
                () -> service.deleteCoordinate(
                        point.id(),
                        new RevisionRequest(created.revision())
                )
        );
        assertEquals(List.of("Pickup trigger"), exception.taskNames());
    }

    @Test
    void deletingTaskAlsoDeletesItsTriggers() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Home");
        TaskListResponse tasks = service.createTask(task(
                "Eat",
                point.id().toString()
        ));
        TaskTriggerListResponse triggers = service.createTaskTrigger(
                new TaskTriggerRequest(
                        "Hunger",
                        TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW,
                        tasks.tasks().getFirst().id(),
                        TaskPriority.NORMAL,
                        20,
                        100,
                        null,
                        null,
                        18,
                        null
                )
        );

        TaskListResponse deleted = service.deleteTask(
                tasks.tasks().getFirst().id(),
                new RevisionRequest(triggers.revision())
        );

        assertEquals(List.of(), deleted.tasks());
        assertEquals(List.of(), service.taskTriggers().triggers());
        assertEquals(List.of(), service.taskRuns().triggers());
    }

    @Test
    void managesFoldersAndClearsAssignmentsWhenAFolderIsDeleted() {
        V2ControlService service = service();
        TaskFolderListResponse created = service.createTaskFolder(
                new TaskFolderRequest(" Farm ")
        );
        var folder = created.folders().getFirst();

        assertEquals("Farm", folder.name());
        assertEquals(List.of(folder), service.taskFolders().folders());
        CoordinatePoint point = createPoint(service, "Field");
        TaskListResponse tasks = service.createTask(task(
                "Harvest",
                point.id().toString(),
                folder.id()
        ));
        assertEquals(folder.id(), tasks.tasks().getFirst().folderId());

        TaskFolderListResponse renamed = service.updateTaskFolder(
                folder.id(),
                new TaskFolderUpdateRequest(tasks.revision(), "Fields")
        );
        assertEquals("Fields", renamed.folders().getFirst().name());
        TaskTriggerListResponse triggers = service.createTaskTrigger(
                new TaskTriggerRequest(
                        "Hunger",
                        TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW,
                        tasks.tasks().getFirst().id(),
                        TaskPriority.NORMAL,
                        20,
                        100,
                        null,
                        null,
                        18,
                        null
                )
        );
        TaskFolderListResponse deleted = service.deleteTaskFolder(
                folder.id(),
                new RevisionRequest(triggers.revision())
        );

        assertEquals(List.of(), deleted.folders());
        TaskListResponse remaining = service.tasks();
        assertEquals(1, remaining.tasks().size());
        assertEquals(null, remaining.tasks().getFirst().folderId());
        assertEquals(1, service.taskTriggers().triggers().size());
    }

    @Test
    void rejectsAssigningATaskToAnUnknownFolder() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Field");

        assertThrows(
                V2ValidationException.class,
                () -> service.createTask(task(
                        "Harvest",
                        point.id().toString(),
                        UUID.randomUUID()
                ))
        );
    }

    @Test
    void preservesFoldersAcrossResourceAndTriggerSaves() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Field");
        TaskFolderListResponse createdFolder = service.createTaskFolder(
                new TaskFolderRequest("Farm")
        );
        var folder = createdFolder.folders().getFirst();
        TaskListResponse tasks = service.createTask(task(
                "Harvest",
                point.id().toString(),
                folder.id()
        ));
        var task = tasks.tasks().getFirst();

        CoordinateListResponse coordinates = service.updateCoordinate(
                point.id(),
                new CoordinateUpdateRequest(
                        tasks.revision(),
                        "Field base",
                        10,
                        65,
                        20
                )
        );
        RegionListResponse regions = service.createRegion(new RegionRequest(
                "Storage",
                100,
                64,
                100,
                110,
                70,
                110
        ));
        CategoryBindingRequest binding = new CategoryBindingRequest(
                7,
                -34,
                7,
                18,
                -34,
                7,
                7,
                -34,
                5,
                18,
                -34,
                6,
                "DIRECT_BLOCK_ID",
                List.of(),
                List.of()
        );
        CategoryZoneListResponse zones = service.createCategoryZone(
                new CategoryZoneRequest("A", List.of(binding))
        );
        service.createTaskTrigger(new TaskTriggerRequest(
                "Hunger",
                TaskTriggerType.FOOD_LEVEL_AT_OR_BELOW,
                task.id(),
                TaskPriority.NORMAL,
                20,
                100,
                null,
                null,
                18,
                null
        ));

        TaskFolderListResponse folders = service.taskFolders();
        TaskListResponse finalTasks = service.tasks();
        assertEquals(1, regions.regions().size());
        assertEquals(1, zones.categoryZones().size());
        assertEquals(List.of(folder), folders.folders());
        assertEquals(folder.id(), finalTasks.tasks().getFirst().folderId());
    }

    @Test
    void protectsCoordinateReferencedByNestedConditionalStep() {
        V2ControlService service = service();
        CoordinatePoint point = createPoint(service, "Home");
        TaskListResponse tasks = service.createTask(new TaskRequest(
                "Nested",
                TaskPriority.NORMAL,
                false,
                List.of(new TaskStepRequest(
                        null,
                        TaskStepKind.IF,
                        "",
                        Map.of("condition", "ALWAYS"),
                        List.of(new TaskStepRequest(
                                null,
                                ConfigValidator.MOVE_TO_COORDINATE,
                                Map.of(
                                        ConfigValidator.COORDINATE_ID,
                                        point.id().toString()
                                )
                        )),
                        List.of()
                )),
                TaskInterruptionMode.DEFAULT,
                null,
                false,
                false
        ));

        assertThrows(
                CoordinateInUseException.class,
                () -> service.deleteCoordinate(
                        point.id(),
                        new RevisionRequest(tasks.revision())
                )
        );
    }

    @Test
    void protectsCreateTaskAndDefaultInterruptionReferences() {
        V2ControlService service = service();
        TaskListResponse target = service.createTask(new TaskRequest(
                "Target",
                List.of(moveOutputStep())
        ));
        var targetTask = target.tasks().getFirst();
        TaskListResponse withParent = service.createTask(new TaskRequest(
                "Parent",
                TaskPriority.NORMAL,
                false,
                List.of(new TaskStepRequest(
                        null,
                        TaskStepKind.CREATE_TASK,
                        "",
                        Map.of(
                                ConfigValidator.TASK_REF,
                                "task:" + targetTask.id(),
                                ConfigValidator.PRIORITY,
                                "BACKGROUND"
                        ),
                        List.of(),
                        List.of()
                )),
                TaskInterruptionMode.DEFAULT,
                null,
                false,
                false
        ));

        TaskInUseException exception = assertThrows(
                TaskInUseException.class,
                () -> service.deleteTask(
                        targetTask.id(),
                        new RevisionRequest(withParent.revision())
                )
        );
        assertEquals(List.of("Parent"), exception.taskNames());

        TaskListResponse cleanup = service.createTask(new TaskRequest(
                "Default cleanup",
                TaskPriority.NORMAL,
                false,
                List.of(moveOutputStep()),
                TaskInterruptionMode.NONE,
                null,
                true,
                false
        ));
        var cleanupTask = cleanup.tasks().stream()
                .filter(task -> task.name().equals("Default cleanup"))
                .findFirst()
                .orElseThrow();
        TaskListResponse defaultTask = service.createTask(new TaskRequest(
                "Default user",
                List.of(moveOutputStep())
        ));

        assertThrows(
                TaskInUseException.class,
                () -> service.deleteTask(
                        cleanupTask.id(),
                        new RevisionRequest(defaultTask.revision())
                )
        );
    }

    private static TaskStepRequest moveOutputStep() {
        return new TaskStepRequest(
                null,
                ConfigValidator.MOVE_TO_COORDINATE,
                Map.of(
                        ConfigValidator.TARGET_REF,
                        "output:container"
                )
        );
    }

    private V2ControlService service() {
        ConfigStore store = new ConfigStore(
                directory.resolve("stowling-" + System.nanoTime() + ".json")
        );
        store.loadOrReset();
        return new V2ControlServiceImpl(
                store,
                new TaskRunManager(new FailedNavigation()),
                List::of
        );
    }

    private static CoordinatePoint createPoint(
            V2ControlService service,
            String name
    ) {
        return service.createCoordinate(
                new CoordinateRequest(name, 10, 64, 20)
        ).coordinates().getFirst();
    }

    private static TaskRequest task(String name, String coordinateId) {
        return new TaskRequest(
                name,
                List.of(new TaskStepRequest(
                        null,
                        ConfigValidator.MOVE_TO_COORDINATE,
                        Map.of(ConfigValidator.COORDINATE_ID, coordinateId)
                ))
        );
    }

    private static TaskRequest task(
            String name,
            String coordinateId,
            UUID folderId
    ) {
        return new TaskRequest(
                name,
                TaskPriority.NORMAL,
                false,
                List.of(new TaskStepRequest(
                        null,
                        ConfigValidator.MOVE_TO_COORDINATE,
                        Map.of(ConfigValidator.COORDINATE_ID, coordinateId)
                )),
                TaskInterruptionMode.DEFAULT,
                null,
                false,
                false,
                folderId
        );
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
