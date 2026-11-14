package dev.stowling.control;

public interface ConfigControl {
    ConfigReloadResult reloadConfig();

    ConfigCoordinatesSnapshot configCoordinates();

    ConfigCoordinatesSnapshot updateConfigCoordinates(
            ConfigCoordinatesUpdateRequest request
    );

    PickupConfigSnapshot pickupConfig();

    PickupConfigSnapshot updatePickupConfig(PickupConfigUpdateRequest request);
}
