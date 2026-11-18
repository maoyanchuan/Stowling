package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
import dev.stowling.v2.model.TaskCleanupPolicy;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskFlowNormalizer;
import dev.stowling.v2.model.TaskInteractionCompiler;
import dev.stowling.v2.model.TaskInterruptionMode;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.model.TaskResumeMode;
import dev.stowling.v2.model.TaskStep;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TaskCleanupPolicyPersistenceTest {
    @TempDir
    Path directory;

    @Test
    void coordinatesAndRegionsSurviveSaveReloadNormalizationAndCompilation() {
        var point = new CoordinatePoint(UUID.randomUUID(), "Destination", 1, 64, 1);
        var region = new RegionDefinition(UUID.randomUUID(), "Destination area", 2, 64, 1, 3, 64, 1);
        for (String reference : List.of("coordinate:" + point.id(), "region:" + region.id())) {
            var policy = new TaskCleanupPolicy(reference, TaskResumeMode.CONTINUE);
            var task = new TaskDefinition(UUID.randomUUID(), "Gather", TaskPriority.NORMAL, false,
                    List.of(TaskStep.action(UUID.randomUUID(), "MOVE_TO_COORDINATE",
                            Map.of("targetRef", "coordinate:" + point.id()))),
                    TaskInterruptionMode.NONE, null, false, false, null, policy);
            var config = new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION, List.of(point),
                    List.of(task), List.of(region), List.of(), List.of());
            var store = new ConfigStore(directory.resolve(task.id() + ".json"));
            store.save(store.loadOrReset().revision(), config);
            var loaded = new ConfigStore(directory.resolve(task.id() + ".json"))
                    .loadOrReset().config().tasks().getFirst();
            assertEquals(policy, loaded.cleanupPolicy());
            assertTrue(loaded.requeueAfterInterruption());
            assertEquals(policy, TaskInteractionCompiler.compile(loaded, List.of()).cleanupPolicy());
            assertTrue(TaskFlowNormalizer.references(loaded, ResourceReference.parse(reference)));
        }
    }

    @Test
    void outputAndTaskReferencesAreNotCleanupDestinations() {
        assertThrows(IllegalArgumentException.class,
                () -> new TaskCleanupPolicy("output:container", TaskResumeMode.RESTART));
        assertThrows(IllegalArgumentException.class, () -> new TaskCleanupPolicy(
                "task:" + UUID.randomUUID(), TaskResumeMode.RESTART));
    }
}
