package dev.stowling.v2.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.cache.RobotWarehouseCache.Box;
import dev.stowling.v2.cache.RobotWarehouseCache.Location;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RobotWarehouseCacheTest {
    @TempDir
    Path directory;

    @Test
    void retainsMixedBoxContentsAcrossRestartAndMovesAnIndexedBoxBetweenContainers() {
        Path path = directory.resolve("warehouse.json");
        Location source = new Location("world-a", new BlockPosition(8, 64, 0));
        Location destination = new Location("world-a", new BlockPosition(1, 64, 0));
        var cache = new RobotWarehouseCache(path);
        var items = Map.of("minecraft:stone", 30, "minecraft:iron_sword", 1);
        cache.stored(source, "box-a", items);
        cache.stored(source, "box-a", items);

        var reopened = new RobotWarehouseCache(path);
        assertEquals(Map.of("box-a", new Box(items, 2)), reopened.boxes(source));
        assertEquals(Map.of(), reopened.boxes(new Location("world-b", source.position())));
        reopened.withdrawn(source, "box-a");
        var filled = Map.of("minecraft:stone", 30, "minecraft:iron_sword", 1,
                "minecraft:dirt", 16);
        reopened.stored(destination, "box-b", filled);

        var after = new RobotWarehouseCache(path);
        assertEquals(Map.of("box-a", new Box(items, 1)), after.boxes(source));
        assertEquals(Map.of("box-b", new Box(filled, 1)), after.boxes(destination));
    }

    @Test
    void reconcilingPhysicalStockDropsMissingBoxesWithoutIndexingOutsideStock() {
        Path path = directory.resolve("warehouse.json");
        Location location = new Location("world-a", new BlockPosition(8, 64, 0));
        var cache = new RobotWarehouseCache(path);
        var items = Map.of("minecraft:stone", 1);
        cache.stored(location, "retained", items);
        cache.stored(location, "missing", items);

        cache.reconcile(location, Map.of("external", 3, "retained", 2));

        assertEquals(Map.of("retained", new Box(items, 1)),
                new RobotWarehouseCache(path).boxes(location));
        cache.withdrawn(location, "retained");
        assertEquals(Map.of(), new RobotWarehouseCache(path).boxes(location));
    }
}
