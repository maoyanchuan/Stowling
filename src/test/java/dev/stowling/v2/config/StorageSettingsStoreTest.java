package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.StorageAutomationSettings;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import java.util.UUID;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageSettingsStoreTest {
    @TempDir
    Path directory;

    @Test
    void overflowFacilitiesSurviveMigrationRestartAndCoordinateResolution() throws IOException {
        ConfigStore config = new ConfigStore(directory.resolve("config.json"));
        var initial = config.loadOrReset();
        UUID pointId = UUID.randomUUID();
        UUID regionId = UUID.randomUUID();
        var point = new CoordinatePoint(pointId, "Overflow", 20, 64, 30);
        var region = new RegionDefinition(regionId, "Overflow area", 21, 64, 30, 22, 64, 31);
        config.save(initial.revision(), new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(point), List.of(), List.of(region), List.of(), List.of()));
        Path file = directory.resolve("storage.json");
        StorageSettingsStore store = new StorageSettingsStore(file);
        store.bindCoordinates(config);
        var settings = new StorageAutomationSettings(false, List.of(), List.of(), null,
                List.of(), List.of(), null, List.of(), List.of(pointId), List.of(regionId));
        store.save(store.read().revision(), settings);
        var reopened = new StorageSettingsStore(file);
        reopened.bindCoordinates(config);
        assertEquals(settings, reopened.read().settings());
        assertEquals(List.of(new BlockPosition(20, 64, 30)), reopened.overflowPositions());
        assertEquals(List.of(region), reopened.overflowRegions());
        assertEquals(List.of(pointId), reopened.resolved().overflowCoordinateIds());
        assertEquals(settings, CoordinateResourceMigration.migrate(config, settings));
    }

    @Test
    void overflowMayNotReuseAnEmptyBoxSupplyCoordinate() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new StorageAutomationSettings(false,
                List.of(), List.of(), null, List.of(), List.of(id), null, List.of(),
                List.of(id), List.of()));
    }

    @Test
    void sharedSettingsSurviveRestartAndRejectStaleEdits() throws IOException {
        Path file = directory.resolve("storage.json");
        StorageSettingsStore store = new StorageSettingsStore(file);
        StorageAutomationSettings settings = new StorageAutomationSettings(true,
                List.of(new BlockPosition(1, 64, 2)), List.of(new BlockPosition(2, 64, 2)),
                new BlockPosition(3, 64, 2));
        String revision = store.read().revision();
        store.save(revision, settings);
        assertEquals(settings, new StorageSettingsStore(file).read().settings());
        assertThrows(IllegalArgumentException.class, () -> store.save(revision, settings));
    }
}
