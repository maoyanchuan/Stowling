package dev.stowling.api;

public final class TokenFileException extends RuntimeException {
    public TokenFileException(String message) {
        super(message);
    }

    public TokenFileException(String message, Throwable cause) {
        super(message, cause);
    }
}
