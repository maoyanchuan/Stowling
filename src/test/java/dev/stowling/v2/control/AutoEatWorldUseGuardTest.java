package dev.stowling.v2.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.model.TaskInteractionCompiler;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.runtime.TaskRunManager;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoEatWorldUseGuardTest {
    @TempDir
    Path directory;

    @Test
    void optInSurvivesSavingReloadCompilationAndListeningChanges() {
        Path file = directory.resolve("config.json");
        var store = new ConfigStore(file);
        store.loadOrReset();
        var service = service(store);
        var defaults = new AutoEatTask.Settings("Meal", "minecraft:bread", null, 16, 1, false);
        assertFalse(defaults.worldUseGuardEnabled());
        for (boolean enabled : List.of(true, false)) {
            var settings = new AutoEatTask.Settings("Meal " + enabled, "minecraft:bread", null,
                    16, 1, false, TaskPriority.URGENT, false, 64, true, enabled);
            var saved = service.saveAutoEatTask(null, service.autoEatTasks().revision(), settings);
            service.setAutoEatListening(saved.taskId(), new RevisionRequest(saved.revision()), true);
            var reopened = service(new ConfigStore(file));
            var item = reopened.autoEatTasks().items().stream()
                    .filter(task -> task.id().equals(saved.taskId())).findFirst().orElseThrow();
            assertEquals(enabled, item.settings().worldUseGuardEnabled());
            var definition = reopened.tasks().tasks().stream()
                    .filter(task -> task.id().equals(saved.taskId())).findFirst().orElseThrow();
            var compiled = TaskInteractionCompiler.compile(definition, List.of());
            assertEquals(enabled, compiled.autoEatOptions().worldUseGuardEnabled());
            var eat = compiled.steps().getFirst().steps().getLast().steps().getLast();
            assertEquals(enabled,
                    Boolean.parseBoolean(eat.parameters().get("worldUseGuardEnabled")));
            assertTrue(item.settings().enabled());
        }
    }

    private static V2ControlServiceImpl service(ConfigStore store) {
        var navigation = new NavigationPort() {
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
        };
        return new V2ControlServiceImpl(store, new TaskRunManager(navigation), List::of);
    }
}
