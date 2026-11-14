package dev.stowling.automation;

@FunctionalInterface
public interface InventoryQueryPort {
    PlayerInventorySnapshot snapshot();
}
