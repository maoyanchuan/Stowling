package dev.stowling.control;

public final class PickupSiteNotFoundException extends RuntimeException {
    public PickupSiteNotFoundException() {
        super("Pickup site not found");
    }
}
