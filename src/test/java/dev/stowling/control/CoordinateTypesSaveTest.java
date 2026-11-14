package dev.stowling.control;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationStep;
import dev.stowling.automation.AutomationStepType;
import dev.stowling.automation.AutomationWorkflow;
import dev.stowling.config.ConfigFileStore;
import dev.stowling.config.ConfigLoader;
import dev.stowling.config.ConfigRevisionConflictException;
import dev.stowling.config.StowlingConfig;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.CoordinateType;
import dev.stowling.domain.NamedCoordinate;
import dev.stowling.inventory.InventoryCapture;
import dev.stowling.inventory.InventoryFreshness;
import dev.stowling.inventory.InventorySource;
import dev.stowling.persistence.SqliteInventoryRepository;
import dev.stowling.persistence.SqliteTaskRepository;
import dev.stowling.persistence.StowlingDatabase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CoordinateTypesSaveTest {
    private static final String DIMENSION = "minecraft:overworld";

    @TempDir
    Path tempDir;

    @Test
    void savesGeometryAndReadsItAfterReloadWithoutEditingTaskSteps() throws IOException {
        NamedCoordinate coordinate = new NamedCoordinate(
                "pickup-range", "Pickup", DIMENSION, new BlockPosition(1, -43, 4),
                CoordinateType.SPHERE, 8, null
        );
        StowlingConfig original = config(coordinate);
        Fixture fixture = fixture("roundtrip", original);
        ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();
        assertEquals(CoordinateType.SPHERE, before.coordinatePoints().getFirst().type());
        assertEquals(8, before.coordinatePoints().getFirst().radius());

        ConfigCoordinatePointInfo area = new ConfigCoordinatePointInfo(
                coordinate.id(), "Updated range", DIMENSION,
                new ContainerPosition(20, 70, 10), CoordinateType.AREA, null,
                new ContainerPosition(-10, -60, -8)
        );
        ConfigCoordinatePointsSnapshot saved = fixture.service().updateCoordinatePoints(
                new ConfigCoordinatePointsUpdateRequest(before.revision(), List.of(area))
        );

        assertEquals(List.of(area), saved.coordinatePoints());
        assertEquals(saved, fixture.service().coordinatePoints());
        StowlingConfig loaded = new ConfigLoader().load(fixture.path());
        assertEquals(CoordinateType.AREA, loaded.coordinatePoints().getFirst().type());
        assertEquals(new BlockPosition(-10, -60, -8),
                loaded.coordinatePoints().getFirst().secondCorner());
        assertEquals(original.automationWorkflows(), loaded.automationWorkflows());
        assertTrue(Files.isRegularFile(backup(fixture.path())));
        assertEquals(original, new ConfigLoader().load(backup(fixture.path())));
    }

    @Test
    void referenceProtectionAndRevisionConflictsKeepPersistedGeometry() throws IOException {
        NamedCoordinate coordinate = new NamedCoordinate(
                "pickup-range", "Pickup", DIMENSION, new BlockPosition(1, -43, 4),
                CoordinateType.CUBE, 8, null
        );
        Fixture fixture = fixture("protected", config(coordinate));
        ConfigCoordinatePointsSnapshot before = fixture.service().coordinatePoints();
        byte[] bytes = Files.readAllBytes(fixture.path());

        assertThrows(InvalidCoordinatePointsException.class, () ->
                fixture.service().updateCoordinatePoints(
                        new ConfigCoordinatePointsUpdateRequest(before.revision(), List.of())
                ));
        ConfigCoordinatePointInfo point = new ConfigCoordinatePointInfo(
                coordinate.id(), coordinate.name(), DIMENSION,
                new ContainerPosition(1, -43, 4)
        );
        assertThrows(InvalidCoordinatePointsException.class, () ->
                fixture.service().updateCoordinatePoints(
                        new ConfigCoordinatePointsUpdateRequest(before.revision(), List.of(point))
                ));
        assertThrows(ConfigRevisionConflictException.class, () ->
                fixture.service().updateCoordinatePoints(
                        new ConfigCoordinatePointsUpdateRequest("stale", before.coordinatePoints())
                ));
        assertArrayEquals(bytes, Files.readAllBytes(fixture.path()));
        assertEquals(before, fixture.service().coordinatePoints());
    }

    private Fixture fixture(String name, StowlingConfig config) {
        Path path = tempDir.resolve(name + ".json");
        ConfigLoader loader = new ConfigLoader();
        loader.write(path, config);
        ConfigFileStore store = new ConfigFileStore(path, loader);
        StowlingDatabase database = new StowlingDatabase(tempDir.resolve(name + ".db"));
        InventorySource source = new InventorySource() {
            @Override
            public String sourceId() {
                return "test";
            }

            @Override
            public CompletableFuture<InventoryCapture> capture(ContainerRef container) {
                return CompletableFuture.completedFuture(InventoryCapture.fromSlots(
                        "test", container.id(), container.dimension(), Instant.EPOCH,
                        InventoryFreshness.FRESH, List.of()
                ));
            }
        };
        StowlingControlServiceImpl service = new StowlingControlServiceImpl(
                config, new SqliteTaskRepository(database), new SqliteInventoryRepository(database),
                source, ignored -> {
                    throw new AssertionError("Saving coordinates must not start pickup");
                }, Runnable::run, () -> loader.load(path), ignored -> { }, store
        );
        return new Fixture(path, service);
    }

    private static StowlingConfig config(NamedCoordinate coordinate) {
        AutomationWorkflow task = new AutomationWorkflow(
                "collect", "Collect", List.of(new AutomationStep(
                        "pickup", AutomationStepType.RUN_PICKUP,
                        Map.of("coordinateId", coordinate.id())
                ))
        );
        return new StowlingConfig(
                StowlingConfig.CURRENT_SCHEMA_VERSION, List.of(coordinate), List.of(), List.of(),
                List.of(), List.of(task), List.of(), List.of(), List.of(),
                new ContainerRef("overflow", DIMENSION, new BlockPosition(40, 64, 0))
        );
    }

    private static Path backup(Path path) {
        return path.resolveSibling(path.getFileName() + ".bak");
    }

    private record Fixture(Path path, StowlingControlServiceImpl service) { }
}
