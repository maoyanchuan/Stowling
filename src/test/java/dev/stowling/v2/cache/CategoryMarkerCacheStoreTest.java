package dev.stowling.v2.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashSet;
import dev.stowling.automation.ContainerInspectionResult;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryMarkerCacheStoreTest {
    @TempDir
    Path directory;

    @Test
    void markerSlotOrderSurvivesInspectionAndReloadInsteadOfBecomingTypeOrder() {
        List<String> slots = List.of("minecraft:glass", "minecraft:dirt", "minecraft:stone");
        var inspected = ContainerInspectionResult.success("read", new LinkedHashSet<>(slots));
        UUID binding = UUID.randomUUID();
        BlockPosition marker = new BlockPosition(7, 64, 2);
        Path path = directory.resolve("ordered-markers.json");
        CategoryMarkerCacheStore store = new CategoryMarkerCacheStore(path);

        store.replaceBindings(Map.of(binding, Map.of(marker, inspected.itemIds())));

        assertEquals(slots, List.copyOf(inspected.itemIds()));
        assertEquals(slots, List.copyOf(new CategoryMarkerCacheStore(path).contents(binding)
                .orElseThrow().get(marker)));
    }

    @Test
    void persistsMarkerContainerContentsAcrossReloads() {
        Path path = directory.resolve("category-cache.json");
        UUID bindingId = UUID.randomUUID();
        BlockPosition marker = new BlockPosition(7, -34, 7);
        CategoryMarkerCacheStore store =
                new CategoryMarkerCacheStore(path);

        store.replaceBindings(Map.of(
                bindingId,
                Map.of(marker, Set.of(
                        "minecraft:oak_log",
                        "minecraft:oak_planks"
                ))
        ));

        CategoryMarkerCacheStore reloaded =
                new CategoryMarkerCacheStore(path);
        Map<BlockPosition, Set<String>> contents = reloaded
                .contents(bindingId)
                .orElseThrow();
        assertEquals(
                Set.of("minecraft:oak_log", "minecraft:oak_planks"),
                contents.get(marker)
        );
        assertTrue(reloaded.contents(UUID.randomUUID()).isEmpty());
    }
}
