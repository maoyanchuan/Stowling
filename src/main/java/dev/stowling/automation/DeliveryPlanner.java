package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.StorageRoute;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

public final class DeliveryPlanner {
    private final StorageRouteMatcher matcher = new StorageRouteMatcher();

    public List<DeliveryIntent> plan(
            UUID taskId,
            ContainerRef buffer,
            ContainerView bufferView,
            List<StorageRoute> routes,
            ContainerRef overflowContainer,
            Function<String, Set<String>> tagsByItem
    ) {
        Objects.requireNonNull(taskId, "taskId");
        Objects.requireNonNull(buffer, "buffer");
        Objects.requireNonNull(bufferView, "bufferView");
        Objects.requireNonNull(routes, "routes");
        Objects.requireNonNull(overflowContainer, "overflowContainer");
        Objects.requireNonNull(tagsByItem, "tagsByItem");
        if (!buffer.id().equals(bufferView.containerId())) {
            throw new IllegalArgumentException("buffer view does not match buffer ref");
        }
        Map<PlacementKey, DeliveryBucket> buckets = new LinkedHashMap<>();
        for (ContainerSlot slot : bufferView.slots()) {
            Optional<StorageRoute> route = matcher.match(
                    slot.itemId(),
                    tagsByItem.apply(slot.itemId()),
                    routes
            );
            PlacementKey key = placementKey(route, overflowContainer);
            DeliveryBucket bucket = buckets.computeIfAbsent(
                    key,
                    ignored -> new DeliveryBucket(key)
            );
            bucket.add(slot.itemId(), slot.count());
        }
        List<DeliveryIntent> result = new ArrayList<>();
        int index = 0;
        for (DeliveryBucket bucket : buckets.values()) {
            result.add(bucket.toIntent(taskId, buffer, index++));
        }
        return List.copyOf(result);
    }

    private PlacementKey placementKey(
            Optional<StorageRoute> route,
            ContainerRef overflow
    ) {
        if (route.isEmpty()) {
            return new PlacementKey(
                    overflow,
                    DeliveryMode.LOOSE,
                    Optional.of("overflow"),
                    Optional.empty()
            );
        }
        StorageRoute matched = route.orElseThrow();
        if (matched.packingEnabled()) {
            return new PlacementKey(
                    matched.bulkContainer(),
                    DeliveryMode.PACK,
                    Optional.of(matched.id()),
                    Optional.of(matched.packingStationId())
            );
        }
        return new PlacementKey(
                matched.bulkContainer(),
                DeliveryMode.LOOSE,
                Optional.of(matched.id()),
                Optional.empty()
        );
    }

    private record PlacementKey(
            ContainerRef destination,
            DeliveryMode mode,
            Optional<String> routeId,
            Optional<String> stationId
    ) {
    }

    private static final class DeliveryBucket {
        private final PlacementKey key;
        private final Map<String, Integer> itemCounts = new LinkedHashMap<>();

        private DeliveryBucket(PlacementKey key) {
            this.key = key;
        }

        private void add(String itemId, int count) {
            itemCounts.merge(itemId, count, Math::addExact);
        }

        private DeliveryIntent toIntent(UUID taskId, ContainerRef buffer, int index) {
            List<ItemAmount> items = itemCounts.entrySet().stream()
                    .map(entry -> new ItemAmount(entry.getKey(), entry.getValue()))
                    .toList();
            String keyText = taskId + ":delivery:" + index;
            return new DeliveryIntent(
                    keyText,
                    buffer,
                    key.destination(),
                    items,
                    key.mode(),
                    key.routeId(),
                    key.stationId()
            );
        }
    }
}
