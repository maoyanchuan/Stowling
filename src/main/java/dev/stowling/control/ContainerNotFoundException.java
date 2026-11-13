package dev.stowling.control;

public final class ContainerNotFoundException extends RuntimeException {
    public ContainerNotFoundException() {
        super("Container not found");
    }
}
