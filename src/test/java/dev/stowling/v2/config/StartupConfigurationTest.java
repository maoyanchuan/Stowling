package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.model.AppConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void reportsTheTaskConfigAndPreservesTheUnusedStorageFile() throws Exception {
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

        assertEquals(1, issues.size());
        assertEquals(config.toString(), issues.getFirst().file());
        assertTrue(issues.getFirst().detail().contains("tasks[0].steps[0]"));
        assertTrue(issues.getFirst().detail().contains("UNRECOGNIZED_MODULE"));
        assertEquals(incompatible, Files.readString(config));
        assertEquals(malformed, Files.readString(storage));

        Files.writeString(config, incompatible.replace("UNRECOGNIZED_MODULE",
                "SYNC_PLAYER_INVENTORY"));

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
    void rejectsOldSchemaAndLeavesBothOriginalFilesInPlace() throws Exception {
        Path config = directory.resolve("stowling.json");
        Path storage = directory.resolve("stowling-storage-settings.json");
        String oldConfig = "{\"schemaVersion\":15,\"coordinates\":[],\"tasks\":[]}";
        String oldFacilities = "{\"temporaryCoordinateIds\":[{}]}";
        Files.writeString(config, oldConfig);
        Files.writeString(storage, oldFacilities);

        var issue = StartupConfiguration.load(directory).issues().getFirst();

        assertEquals(config.toString(), issue.file());
        assertTrue(issue.detail().contains("schemaVersion 15"));
        assertEquals(oldConfig, Files.readString(config));
        assertEquals(oldFacilities, Files.readString(storage));
    }
}
