package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record DeliveryIntent(
        String key,
        ContainerRef buffer,
        ContainerRef destination,
        List<ItemAmount> items,
        DeliveryMode mode,
        Optional<String> routeId,
        Optional<String> packingStationId
) {
    public DeliveryIntent {
        key = requireText(key, "key");
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(destination, "destination");
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        Objects.requireNonNull(mode, "mode");
        routeId = Objects.requireNonNull(routeId, "routeId");
        packingStationId = Objects.requireNonNull(packingStationId, "packingStationId");
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (mode == DeliveryMode.PACK && packingStationId.isEmpty()) {
            throw new IllegalArgumentException("packing delivery requires a station id");
        }
        if (mode == DeliveryMode.LOOSE && packingStationId.isPresent()) {
            throw new IllegalArgumentException("loose delivery cannot have a station id");
        }
    }

    public ContainerTransfer asTransfer() {
        return new ContainerTransfer(key, Optional.of(buffer), destination, items);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
