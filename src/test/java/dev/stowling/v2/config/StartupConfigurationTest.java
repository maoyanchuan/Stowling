package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.StorageAutomationSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void reportsBothFilesAndLoadsThemAfterTheyAreCorrected() throws Exception {
        Path config = directory.resolve("stowling.json");
        Path storage = directory.resolve("stowling-storage-settings.json");
        String incompatible = """
                {"schemaVersion":%d,"coordinates":[],"tasks":[
                    {"id":"00000000-0000-0000-0000-000000000001","name":"Sort","steps":[
                        {"id":"00000000-0000-0000-0000-000000000002",
                         "moduleId":"UNRECOGNIZED_MODULE","parameters":{}}
                    ]}
                ]}
                """.formatted(AppConfig.CURRENT_SCHEMA_VERSION);
        String malformed = "{\"temporaryCoordinateIds\":[{}]}";
        Files.writeString(config, incompatible);
        Files.writeString(storage, malformed);

        var issues = StartupConfiguration.load(directory).issues();

        assertEquals(2, issues.size());
        assertEquals(config.toString(), issues.getFirst().file());
        assertTrue(issues.getFirst().detail().contains("tasks[0].steps[0]"));
        assertTrue(issues.getFirst().detail().contains("UNRECOGNIZED_MODULE"));
        assertEquals(storage.toString(), issues.getLast().file());
        assertTrue(issues.getLast().detail().contains("temporaryCoordinateIds[0]"));
        assertEquals(incompatible, Files.readString(config));
        assertEquals(malformed, Files.readString(storage));

        Files.writeString(config, incompatible.replace("UNRECOGNIZED_MODULE",
                "SYNC_PLAYER_INVENTORY"));
        Files.writeString(storage, "{}");

        var corrected = StartupConfiguration.load(directory);
        assertTrue(corrected.issues().isEmpty());
        assertEquals(1, corrected.configs().read().config().tasks().size());
    }

    @Test
    void identifiesTheUnsupportedSchemaVersionBeforeValidatingItsFields() throws Exception {
        int futureVersion = AppConfig.CURRENT_SCHEMA_VERSION + 1;
        Path config = directory.resolve("stowling.json");
        Files.writeString(config, "{\"schemaVersion\":" + futureVersion + ",\"newField\":[]}");

        var issue = StartupConfiguration.load(directory).issues().getFirst();

        assertEquals(config.toString(), issue.file());
        assertTrue(issue.detail().contains("schemaVersion " + futureVersion));
    }

    @Test
    void preservesBothFilesWhenLegacyFacilitiesConflictAfterMigration() throws Exception {
        Path config = directory.resolve("stowling.json");
        ConfigStore configs = new ConfigStore(config);
        var initial = configs.loadOrReset();
        var supply = new CoordinatePoint(UUID.randomUUID(), "Supply", 1, 64, 2);
        configs.save(initial.revision(), new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(supply), List.of()));

        Path storage = directory.resolve("stowling-storage-settings.json");
        StorageSettingsStore settings = new StorageSettingsStore(storage);
        settings.save(settings.read().revision(), new StorageAutomationSettings(
                false, List.of(new BlockPosition(1, 64, 2), new BlockPosition(3, 64, 4)),
                List.of(), null, List.of(), List.of(supply.id()), null));
        String configBefore = Files.readString(config);
        String storageBefore = Files.readString(storage);

        var issue = StartupConfiguration.load(directory).issues().getFirst();

        assertTrue(issue.file().contains(config.toString()));
        assertTrue(issue.file().contains(storage.toString()));
        assertTrue(issue.detail().contains("暂存与供应不能引用同一坐标"));
        assertEquals(configBefore, Files.readString(config));
        assertEquals(storageBefore, Files.readString(storage));
    }
}
