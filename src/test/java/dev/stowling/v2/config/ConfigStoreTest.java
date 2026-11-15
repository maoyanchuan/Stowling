package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {
    @TempDir
    Path directory;

    @Test
    void resetsOldSchemaAndCreatesEmptySchemaSevenConfig() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, "{\"schemaVersion\":6,\"pickupRegions\":[]}");
        ConfigStore store = new ConfigStore(path);

        V2ConfigSnapshot snapshot = store.loadOrReset();

        assertEquals(7, snapshot.config().schemaVersion());
        assertTrue(snapshot.config().coordinates().isEmpty());
        assertTrue(snapshot.config().tasks().isEmpty());
        assertTrue(Files.isRegularFile(directory.resolve("stowling.json.bak")));
        assertEquals(
                "{\"schemaVersion\":6,\"pickupRegions\":[]}",
                Files.readString(directory.resolve("stowling.json.bak"))
        );
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
    void keepsACurrentSchemaSevenConfiguration() throws Exception {
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
    void resetsSchemaSevenThatContainsLegacyFields() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 7,
                    "coordinates": [],
                    "tasks": [],
                    "radius": 8
                }
                """);
        ConfigStore store = new ConfigStore(path);

        V2ConfigSnapshot snapshot = store.loadOrReset();

        assertEquals(7, snapshot.config().schemaVersion());
        assertTrue(snapshot.config().coordinates().isEmpty());
        assertTrue(Files.isRegularFile(directory.resolve("stowling.json.bak")));
    }
}
