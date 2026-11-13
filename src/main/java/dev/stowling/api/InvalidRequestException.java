package dev.stowling.api;

final class InvalidRequestException extends Exception {
    InvalidRequestException(String message) {
        super(message);
    }
}
