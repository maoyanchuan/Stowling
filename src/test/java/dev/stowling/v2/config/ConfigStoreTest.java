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
    void keepsCoordinatesThatPredateOptionalFields() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 7,
                    "coordinates": [{
                        "id": "00000000-0000-0000-0000-000000000001",
                        "name": "Home",
                        "x": 0,
                        "y": 64,
                        "z": 0
                    }],
                    "tasks": []
                }
                """);
        ConfigStore store = new ConfigStore(path);

        CoordinatePoint point = store.loadOrReset().config().coordinates().getFirst();

        assertEquals(null, point.dimension());
        assertEquals(null, point.radius());
        assertTrue(store.loadOrReset().config().categoryZones().isEmpty());
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void keepsTasksThatPredatePriorityAndRepeatFields() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 7,
                    "coordinates": [{
                        "id": "00000000-0000-0000-0000-000000000001",
                        "name": "Home",
                        "x": 0,
                        "y": 64,
                        "z": 0
                    }],
                    "tasks": [{
                        "id": "00000000-0000-0000-0000-000000000002",
                        "name": "Travel",
                        "steps": [{
                            "id": "00000000-0000-0000-0000-000000000003",
                            "moduleId": "MOVE_TO_COORDINATE",
                            "parameters": {
                                "coordinateId":
                                    "00000000-0000-0000-0000-000000000001"
                            }
                        }]
                    }]
                }
                """);
        ConfigStore store = new ConfigStore(path);

        var task = store.loadOrReset().config().tasks().getFirst();

        assertEquals(dev.stowling.v2.model.TaskPriority.NORMAL, task.priority());
        assertEquals(false, task.repeatWhenIdle());
        assertTrue(!Files.exists(directory.resolve("stowling.json.bak")));
    }

    @Test
    void keepsCategoryBindingsThatPredateIgnoredSupportBlocks() throws Exception {
        Path path = directory.resolve("stowling.json");
        Files.writeString(path, """
                {
                    "schemaVersion": 7,
                    "coordinates": [],
                    "tasks": [],
                    "categoryZones": [{
                        "id": "00000000-0000-0000-0000-000000000002",
                        "name": "A",
                        "bindings": [{
                            "id": "00000000-0000-0000-0000-000000000003",
                            "markerArea": {
                                "firstX": 7, "firstY": -34, "firstZ": 7,
                                "secondX": 18, "secondY": -34, "secondZ": 7
                            },
                            "storageArea": {
                                "firstX": 7, "firstY": -34, "firstZ": 5,
                                "secondX": 18, "secondY": -34, "secondZ": 6
                            },
                            "ruleId": "DIRECT_BLOCK_ID"
                        }]
                    }]
                }
                """);
        ConfigStore store = new ConfigStore(path);

        assertTrue(store.loadOrReset().config().categoryZones()
                .getFirst()
                .bindings()
                .getFirst()
                .ignoredMarkerBlockIds()
                .isEmpty());
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
