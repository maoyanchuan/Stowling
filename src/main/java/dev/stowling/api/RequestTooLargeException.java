package dev.stowling.api;

final class RequestTooLargeException extends Exception {
    RequestTooLargeException() {
        super("Request body exceeds the configured limit");
    }
}
