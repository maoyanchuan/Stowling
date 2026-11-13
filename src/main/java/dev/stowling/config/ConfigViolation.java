package dev.stowling.config;

import java.util.Objects;

public record ConfigViolation(String path, String message) {
    public ConfigViolation {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(message, "message");
        if (path.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
