package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.StorageAutomationSettings;
import dev.stowling.v2.model.TaskDefinition;
import dev.stowling.v2.model.TaskStep;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinateResourceMigrationTest {
    @TempDir
    Path directory;

    @Test
    void legacyFacilitiesAndManualMoveBecomeReusableReferencesWithoutDuplication() {
        ConfigStore config = new ConfigStore(directory.resolve("config.json"));
        var initial = config.loadOrReset();
        TaskDefinition task = new TaskDefinition(UUID.randomUUID(), "Move", List.of(
                TaskStep.action(UUID.randomUUID(), "MOVE_TO_COORDINATE",
                        Map.of("x", "1", "y", "64", "z", "2"))));
        config.save(initial.revision(), new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(), List.of(task)));
        var legacy = new StorageAutomationSettings(true,
                List.of(new BlockPosition(1, 64, 2)), List.of(new BlockPosition(3, 64, 2)),
                new BlockPosition(4, 64, 2));
        var migrated = CoordinateResourceMigration.migrate(config, legacy);
        assertEquals(3, config.read().config().coordinates().size());
        assertTrue(migrated.temporaryContainers().isEmpty());
        assertEquals("coordinate:" + migrated.temporaryCoordinateIds().getFirst(),
                config.read().config().tasks().getFirst().steps().getFirst()
                        .parameters().get("targetRef"));
        assertEquals(migrated, CoordinateResourceMigration.migrate(config, migrated));
        assertEquals(3, config.read().config().coordinates().size());
    }
}
