package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.StorageAutomationSettings;
import dev.stowling.v2.model.AppConfig;
import dev.stowling.v2.model.CoordinatePoint;
import dev.stowling.v2.model.RegionDefinition;
import dev.stowling.v2.model.ResourceReference;
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
        UUID unstackableId = UUID.randomUUID();
        var point = new CoordinatePoint(pointId, "Overflow", 20, 64, 30,
                "minecraft:overworld", null);
        var region = new RegionDefinition(regionId, "Overflow area", 21, 64, 30, 22, 64, 31);
        var unstackable = new RegionDefinition(unstackableId, "Unstackable", 31, 64, 30,
                32, 64, 31);
        config.save(initial.revision(), new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(point), List.of(), List.of(region, unstackable), List.of(), List.of()));
        Path file = directory.resolve("storage.json");
        StorageSettingsStore store = new StorageSettingsStore(file);
        store.bindCoordinates(config);
        var settings = new StorageAutomationSettings(false, List.of(), List.of(), null,
                List.of(), List.of(), null, List.of(), List.of(pointId), List.of(regionId),
                unstackableId);
        store.save(store.read().revision(), settings);
        var reopened = new StorageSettingsStore(file);
        reopened.bindCoordinates(config);
        assertEquals(settings, reopened.read().settings());
        assertEquals(List.of(new BlockPosition(20, 64, 30)), reopened.overflowPositions());
        assertEquals(reopened.overflowPositions(), reopened.overflowPositions("minecraft:overworld"));
        assertEquals(List.of(), reopened.overflowPositions("minecraft:the_nether"));
        assertEquals(List.of(region), reopened.overflowRegions());
        assertEquals(List.of(region), reopened.overflowRegions(regionId));
        assertEquals(List.of(), reopened.overflowRegions(unstackableId));
        assertEquals(reopened.overflowPositions(), reopened.overflowPositions(null, pointId));
        assertEquals(List.of(), reopened.overflowPositions(null, regionId));
        assertEquals(unstackable, reopened.unstackableOverflowRegion());
        assertEquals(unstackableId, reopened.resolved().unstackableOverflowRegionId());
        assertEquals(List.of(pointId), reopened.resolved().overflowCoordinateIds());
        assertEquals(settings, CoordinateResourceMigration.migrate(config, settings));
        assertFalse(reopened.resolved().warehouseEnabled());

        var disabled = new StorageAutomationSettings(false, List.of(), List.of(), null,
                List.of(), List.of(), null, List.of(), List.of(pointId), List.of(regionId),
                unstackableId, false);
        reopened.save(reopened.read().revision(), disabled);
        var disabledAfterRestart = new StorageSettingsStore(file);
        disabledAfterRestart.bindCoordinates(config);
        assertFalse(disabledAfterRestart.resolved().warehouseEnabled());
        assertFalse(disabledAfterRestart.resolved()
                .hasWarehouseLocation(ResourceReference.parse("coordinate:" + pointId)));
    }

    @Test
    void unstackableOverflowCannotReferenceTheOrdinaryOverflowRegion() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new StorageAutomationSettings(false,
                List.of(), List.of(), null, List.of(), List.of(), null, List.of(),
                List.of(), List.of(id), id));
    }

    @Test
    void robotWarehouseKeepsSeparatePointsAndRegionsAcrossMigrationAndRestart() throws IOException {
        ConfigStore config = new ConfigStore(directory.resolve("config.json"));
        var initial = config.loadOrReset();
        var overflow = new CoordinatePoint(UUID.randomUUID(), "Overflow", 1, 64, 0);
        var warehouse = new CoordinatePoint(UUID.randomUUID(), "Robot", 10, 64, 0,
                "minecraft:overworld", null);
        var overflowArea = new RegionDefinition(UUID.randomUUID(), "Overflow area",
                2, 64, 0, 3, 64, 1);
        var warehouseArea = new RegionDefinition(UUID.randomUUID(), "Robot area",
                11, 64, 0, 12, 64, 1);
        config.save(initial.revision(), new AppConfig(AppConfig.CURRENT_SCHEMA_VERSION,
                List.of(overflow, warehouse), List.of(), List.of(overflowArea, warehouseArea),
                List.of(), List.of()));
        var settings = new StorageAutomationSettings(false, List.of(), List.of(), null,
                List.of(), List.of(), null, List.of(), List.of(overflow.id()),
                List.of(overflowArea.id()), null, false, List.of(warehouse.id()),
                List.of(warehouseArea.id()));
        Path file = directory.resolve("storage.json");
        var store = new StorageSettingsStore(file);
        store.bindCoordinates(config);
        store.save(store.read().revision(), settings);

        var reopened = new StorageSettingsStore(file);
        reopened.bindCoordinates(config);

        assertEquals(settings, reopened.read().settings());
        assertEquals(settings, CoordinateResourceMigration.migrate(config, settings));
        assertEquals(List.of(new BlockPosition(1, 64, 0)), reopened.overflowPositions());
        assertEquals(List.of(overflowArea), reopened.overflowRegions());
        assertEquals(List.of(new BlockPosition(10, 64, 0)),
                reopened.robotWarehousePositions("minecraft:overworld", null));
        assertEquals(List.of(), reopened.robotWarehousePositions("minecraft:the_nether", null));
        assertEquals(List.of(), reopened.robotWarehousePositions(null, overflow.id()));
        assertEquals(List.of(warehouseArea), reopened.robotWarehouseRegions(null));
        assertEquals(List.of(), reopened.robotWarehouseRegions(overflowArea.id()));
        assertEquals(List.of(warehouse.id()), reopened.resolved().robotWarehouseCoordinateIds());
        assertTrue(reopened.resolved().warehouseEnabled());
        assertTrue(reopened.resolved().hasWarehouseLocation(
                ResourceReference.parse("coordinate:" + warehouse.id())));
        assertFalse(reopened.resolved().hasWarehouseLocation(
                ResourceReference.parse("coordinate:" + overflow.id())));
    }

    @Test
    void legacyOverflowSettingsAreNotAdoptedAsARobotWarehouse() throws IOException {
        Path file = directory.resolve("legacy-storage.json");
        UUID id = UUID.randomUUID();
        java.nio.file.Files.writeString(file,
                "{\"overflowCoordinateIds\":[\"" + id + "\"],\"robotWarehouseEnabled\":true}");

        var settings = new StorageSettingsStore(file).read().settings();

        assertEquals(List.of(id), settings.overflowCoordinateIds());
        assertEquals(List.of(), settings.robotWarehouseCoordinateIds());
        assertEquals(List.of(), settings.robotWarehouseRegionIds());
        assertFalse(settings.warehouseEnabled());
    }

    @Test
    void robotWarehouseCannotReuseTheSameOverflowFacilityReference() {
        UUID id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new StorageAutomationSettings(false,
                List.of(), List.of(), null, List.of(), List.of(), null, List.of(),
                List.of(id), List.of(), null, true, List.of(id), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new StorageAutomationSettings(false,
                List.of(), List.of(), null, List.of(), List.of(), null, List.of(),
                List.of(), List.of(id), null, true, List.of(), List.of(id)));
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
