package dev.stowling.automation;

import dev.stowling.domain.ContainerRef;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ContainerTransfer(
        String intentKey,
        Optional<ContainerRef> source,
        ContainerRef destination,
        List<ItemAmount> items
) {
    public ContainerTransfer {
        Objects.requireNonNull(intentKey, "intentKey");
        if (intentKey.isBlank()) {
            throw new IllegalArgumentException("intentKey must not be blank");
        }
        source = Objects.requireNonNull(source, "source");
        destination = Objects.requireNonNull(destination, "destination");
        items = aggregate(Objects.requireNonNull(items, "items"));
        if (items.isEmpty()) {
            throw new IllegalArgumentException("items must not be empty");
        }
        if (source.isPresent()) {
            ContainerRef sourceRef = source.orElseThrow();
            if (sourceRef.equals(destination)) {
                throw new IllegalArgumentException("source and destination must differ");
            }
            if (!sourceRef.dimension().equals(destination.dimension())) {
                throw new IllegalArgumentException("cross-dimension transfers are unsupported");
            }
        }
    }

    private static List<ItemAmount> aggregate(List<ItemAmount> values) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (ItemAmount value : values) {
            counts.merge(value.itemId(), value.count(), Math::addExact);
        }
        return counts.entrySet().stream()
                .map(entry -> new ItemAmount(entry.getKey(), entry.getValue()))
                .toList();
    }
}
