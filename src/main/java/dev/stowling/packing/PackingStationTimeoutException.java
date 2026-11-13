package dev.stowling.packing;

public final class PackingStationTimeoutException extends PackingStationException {
    public PackingStationTimeoutException(String message) {
        super(message);
    }

    public PackingStationTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
