package dev.stowling.v2.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.config.ConfigStore;
import dev.stowling.v2.model.TaskInteractionCompiler;
import dev.stowling.v2.model.TaskPriority;
import dev.stowling.v2.runtime.TaskRunManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AutoEatWorldUseGuardTest {
    @TempDir
    Path directory;

    @Test
    void retiresAllSavedSwitchValuesWithoutChangingMealSettingsOrListening() throws Exception {
        var gson = new Gson();
        var original = new AutoEatTask.Settings("Meal", "minecraft:bread", null,
                16, 3, false, TaskPriority.URGENT, false, 23, false);
        for (Boolean option : java.util.Arrays.asList(null, false, true)) {
            for (Boolean parameter : java.util.Arrays.asList(null, false, true)) {
                Path file = directory.resolve("config-" + option + "-" + parameter + ".json");
                var store = new ConfigStore(file);
                store.loadOrReset();
                var service = service(store);
                var saved = service.saveAutoEatTask(
                        null, service.autoEatTasks().revision(), original);
                service.setAutoEatListening(
                        saved.taskId(), new RevisionRequest(saved.revision()), true);
                var json = gson.toJsonTree(store.read().config()).getAsJsonObject();
                var task = json.getAsJsonArray("tasks").get(0).getAsJsonObject();
                if (option != null) {
                    task.getAsJsonObject("autoEatOptions")
                            .addProperty("worldUseGuardEnabled", option);
                }
                var eatStep = task.getAsJsonArray("steps").get(0).getAsJsonObject()
                        .getAsJsonArray("steps").get(0).getAsJsonObject()
                        .getAsJsonArray("steps").get(0).getAsJsonObject();
                if (parameter != null) {
                    eatStep.getAsJsonObject("parameters")
                            .addProperty("worldUseGuardEnabled", parameter.toString());
                }
                Files.writeString(file, json.toString());
                var migrated = new ConfigStore(file);
                var snapshot = migrated.read();
                migrated.save(snapshot.revision(), snapshot.config());
                var reopened = service(new ConfigStore(file));
                var item = reopened.autoEatTasks().items().getFirst();
                assertEquals(saved.taskId(), item.id());
                assertEquals(original.foodItemId(), item.settings().foodItemId());
                assertEquals(original.priority(), item.settings().priority());
                assertEquals(original.refillCount(), item.settings().refillCount());
                assertEquals(original.preferInventory(), item.settings().preferInventory());
                assertEquals(original.intervalSeconds(), item.settings().intervalSeconds());
                assertEquals(original.foodLevelThreshold(), item.settings().foodLevelThreshold());
                assertTrue(item.settings().enabled());
                var compiled = TaskInteractionCompiler.compile(
                        reopened.tasks().tasks().getFirst(), List.of());
                assertFalse(gson.toJson(compiled).contains("worldUseGuardEnabled"));
                assertFalse(gson.toJson(item).contains("worldUseGuardEnabled"));
                assertFalse(Files.readString(file).contains("worldUseGuardEnabled"));
            }
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
