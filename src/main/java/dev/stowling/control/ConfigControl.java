package dev.stowling.control;

public interface ConfigControl {
    ConfigReloadResult reloadConfig();

    ConfigCoordinatePointsSnapshot coordinatePoints();

    ConfigCoordinatePointsSnapshot updateCoordinatePoints(
            ConfigCoordinatePointsUpdateRequest request
    );

    ConfigCoordinatesSnapshot configCoordinates();

    ConfigCoordinatesSnapshot updateConfigCoordinates(
            ConfigCoordinatesUpdateRequest request
    );

    PickupConfigSnapshot pickupConfig();

    PickupConfigSnapshot updatePickupConfig(PickupConfigUpdateRequest request);
}
