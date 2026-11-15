package dev.stowling.v2.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.config.ConfigValidator;
import dev.stowling.v2.config.V2RevisionConflictException;
import dev.stowling.v2.config.V2ValidationException;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.runtime.TaskRunManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class V2ControlServiceImplTest {
    @TempDir
    Path directory;

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
                point.id().toString(),
                service.tasks().tasks().getFirst()
                        .steps().getFirst()
                        .parameters()
                        .get(ConfigValidator.COORDINATE_ID)
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
