package dev.stowling.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.StorageRoute;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigLoaderTest {
    private final ConfigLoader loader = new ConfigLoader();

    @TempDir
    Path tempDir;

    @Test
    void loadsAndValidatesExampleConfiguration() {
        Path path = Path.of("src/main/resources/stowling.example.json");

        StowlingConfig config = loader.load(path);

        assertEquals(1, config.schemaVersion());
        assertEquals(Duration.ofSeconds(60), config.defaultIdleDelay());
        assertEquals("overflow", config.overflowContainer().id());
    }

    @Test
    void rejectsMalformedJson() {
        ConfigLoadException exception = assertThrows(
                ConfigLoadException.class,
                () -> loader.parse("{")
        );

        assertTrue(exception.getMessage().contains("parse"));
    }

    @Test
    void rejectsUnknownRootFields() {
        ConfigLoadException exception = assertThrows(
                ConfigLoadException.class,
                () -> loader.parse("{\"unexpected\": true}")
        );

        assertTrue(exception.getMessage().contains("Unknown configuration field"));
    }

    @Test
    void writesConfigurationWithDurationAsWholeSeconds() {
        Path output = Path.of("build", "tmp", "config-round-trip.json");
        StorageRoute route = ConfigTestFixtures.route(
                "items",
                List.of("minecraft:stone"),
                1
        );
        StowlingConfig config = ConfigTestFixtures.config(List.of(route));

        loader.write(output, config);
        StowlingConfig loaded = loader.load(output);

        assertEquals(config, loaded);
    }

    @Test
    void atomicWriteCreatesBackupOfPreviousConfiguration() throws IOException {
        Path path = tempDir.resolve("stowling.json");
        StowlingConfig original = ConfigTestFixtures.config(List.of());
        StowlingConfig updated = ConfigTestFixtures.config(List.of(
                ConfigTestFixtures.route("updated", List.of("minecraft:stone"), 1)
        ));
        loader.write(path, original);

        loader.writeAtomicWithBackup(path, updated);

        assertEquals(updated, loader.load(path));
        assertEquals(original, loader.load(tempDir.resolve("stowling.json.bak")));
        assertTrue(Files.isRegularFile(tempDir.resolve("stowling.json.bak")));
    }

    @Test
    void failedAtomicReplacementLeavesOriginalConfigurationIntact() {
        Path path = tempDir.resolve("stowling.json");
        StowlingConfig original = ConfigTestFixtures.config(List.of());
        StowlingConfig updated = ConfigTestFixtures.config(List.of(
                ConfigTestFixtures.route("updated", List.of("minecraft:stone"), 1)
        ));
        loader.write(path, original);
        ConfigLoader failingLoader = new ConfigLoader((source, target) -> {
            throw new IOException("simulated atomic replacement failure");
        });

        assertThrows(
                ConfigLoadException.class,
                () -> failingLoader.writeAtomicWithBackup(path, updated)
        );

        assertEquals(original, loader.load(path));
    }
}
