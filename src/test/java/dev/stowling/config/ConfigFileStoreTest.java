package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileStoreTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsStaleRevisionWithoutChangingFile() {
        Path path = tempDir.resolve("stowling.json");
        ConfigLoader loader = new ConfigLoader();
        StowlingConfig original = ConfigTestFixtures.config(List.of());
        StowlingConfig updated = ConfigTestFixtures.config(List.of(
                ConfigTestFixtures.route("updated", List.of("minecraft:stone"), 1)
        ));
        loader.write(path, original);
        ConfigFileStore store = new ConfigFileStore(path, loader);
        ConfigFileSnapshot snapshot = store.read();

        assertThrows(
                ConfigRevisionConflictException.class,
                () -> store.save("stale-revision", updated)
        );

        assertEquals(original, store.read().config());
        assertEquals(snapshot.revision(), store.read().revision());
    }

    @Test
    void successfulSaveReturnsRevisionOfNewRawContent() {
        Path path = tempDir.resolve("stowling.json");
        ConfigLoader loader = new ConfigLoader();
        StowlingConfig original = ConfigTestFixtures.config(List.of());
        StowlingConfig updated = ConfigTestFixtures.config(List.of(
                ConfigTestFixtures.route("updated", List.of("minecraft:stone"), 1)
        ));
        loader.write(path, original);
        ConfigFileStore store = new ConfigFileStore(path, loader);
        String originalRevision = store.read().revision();

        ConfigFileSnapshot saved = store.save(originalRevision, updated);

        assertEquals(updated, saved.config());
        assertNotEquals(originalRevision, saved.revision());
        assertEquals(saved.revision(), store.read().revision());
    }
}
