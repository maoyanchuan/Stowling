package dev.stowling.automation;

public enum PickupStage {
    NAVIGATE_PICKUP,
    COLLECT,
    DEPOSIT_BUFFER,
    WAIT_FOR_IDLE,
    CLASSIFY_BUFFER,
    DELIVER_LOOSE,
    PACK,
    COMPLETE,
    PAUSED,
    FAILED
}
