package dev.stowling.packing;

public class PackingStationException extends Exception {
    public PackingStationException(String message) {
        super(message);
    }

    public PackingStationException(String message, Throwable cause) {
        super(message, cause);
    }
}
