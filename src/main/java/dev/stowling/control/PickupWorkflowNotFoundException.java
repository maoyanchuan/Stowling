package dev.stowling.control;

public final class PickupWorkflowNotFoundException extends RuntimeException {
    public PickupWorkflowNotFoundException() {
        super("Pickup workflow not found");
    }
}
