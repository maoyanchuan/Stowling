package dev.stowling.api;

import java.util.Objects;

record ApiErrorResponse(ApiError error) {
    ApiErrorResponse {
        error = Objects.requireNonNull(error, "error");
    }
}
