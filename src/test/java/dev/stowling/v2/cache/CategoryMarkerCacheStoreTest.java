package dev.stowling.v2.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CategoryMarkerCacheStoreTest {
    @TempDir
    Path directory;

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
