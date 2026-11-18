package dev.stowling.v2.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.StorageAutomationSettings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageSettingsStoreTest {
    @TempDir
    Path directory;

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
