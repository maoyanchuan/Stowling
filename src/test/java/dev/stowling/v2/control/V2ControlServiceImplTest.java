package dev.stowling.v2.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import dev.stowling.v2.category.CategoryMarkerSource;
import dev.stowling.v2.model.CategoryZoneDefinition;
import dev.stowling.v2.model.CategoryZoneExpansionAxis;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskStepKind;
import dev.stowling.v2.model.TaskTriggerDefinition;
import dev.stowling.v2.model.TaskTriggerType;
import dev.stowling.v2.runtime.TaskRunManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V2ControlServiceImplTest {
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
                        List.of(new AutoClassifyAreaRequest(
                                "category-zone:" + zone.id(),
                                "DIRECT_BLOCK_ID",
                                List.of(CategoryMarkerSource.PLACED_ITEM)
                        ), new AutoClassifyAreaRequest(
                                "category-zone:" + containerZone.id(),
                                "CONTAINER_CONTENTS",
                                List.of(
                                        CategoryMarkerSource.CONTAINER_CONTENTS
                                )
                        )),
                        TaskPriority.NORMAL
                )
        );

        assertEquals(0, service.tasks().tasks().size());
        assertEquals(1, service.autoClassifyTasks().items().size());
        assertEquals(false, created.item().enabled());
        assertTrue(created.item().collectionEnabled());
        assertTrue(created.item().classificationEnabled());
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
                        List.of(new AutoClassifyAreaRequest(
                                "category-zone:" + zone.id(),
                                "DIRECT_BLOCK_ID",
                                List.of(CategoryMarkerSource.PLACED_ITEM)
                        )),
                        TaskPriority.NORMAL
                )
        );
        assertTrue(!collision.item().name().equals("自动分类 分类"));
        assertTrue(collision.item().collectionEnabled());
        assertTrue(collision.item().classificationEnabled());
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
                                List.of(new AutoClassifyAreaRequest(
                                        "category-zone:" + zone.id(),
                                        "DIRECT_BLOCK_ID",
                                        List.of(
                                                CategoryMarkerSource.PLACED_ITEM
                                        )
                                )),
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
    void createsAutoFillCategoryZoneFromSingleTemplate() {
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
                        )),
                        new CategoryZoneExpansionRequest(
                                0,
                                63,
                                17,
                                24,
                                65,
                                21,
                                "X_POSITIVE",
                                10
                        )
                )
        ).categoryZones().getFirst();

        assertEquals(3, zone.bindings().size());
        assertEquals(
                CategoryZoneExpansionAxis.X_POSITIVE,
                zone.expansion().direction()
        );
        assertEquals(10, zone.expansion().step());
        assertEquals(20, zone.bindings().get(2).markerArea().minX());
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
