package dev.stowling.api;

import java.util.Objects;

record ApiError(String code, String message) {
    ApiError {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
    }
}
