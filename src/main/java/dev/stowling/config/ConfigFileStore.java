package dev.stowling.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

public final class ConfigFileStore {
    private final Path path;
    private final ConfigLoader loader;

    public ConfigFileStore(Path path, ConfigLoader loader) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath();
        this.loader = Objects.requireNonNull(loader, "loader");
    }

    public ConfigFileSnapshot read() {
        byte[] content = readContent();
        StowlingConfig config = loader.parse(new String(content, StandardCharsets.UTF_8));
        return new ConfigFileSnapshot(revision(content), config);
    }

    public ConfigFileSnapshot save(String expectedRevision, StowlingConfig config) {
        Objects.requireNonNull(expectedRevision, "expectedRevision");
        ConfigValidator.requireValid(config);
        byte[] currentContent = readContent();
        if (!revision(currentContent).equals(expectedRevision)) {
            throw new ConfigRevisionConflictException();
        }
        loader.writeAtomicWithBackup(path, config);
        return read();
    }

    private byte[] readContent() {
        try {
            return Files.readAllBytes(path);
        } catch (IOException exception) {
            throw new ConfigLoadException("Unable to read config: " + path, exception);
        }
    }

    static String revision(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
