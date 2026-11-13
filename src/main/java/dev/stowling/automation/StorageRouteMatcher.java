package dev.stowling.automation;

import dev.stowling.domain.StorageRoute;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class StorageRouteMatcher {
    public Optional<StorageRoute> match(
            String itemId,
            Set<String> itemTags,
            List<StorageRoute> routes
    ) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(itemTags, "itemTags");
        Objects.requireNonNull(routes, "routes");
        List<StorageRoute> matches = new ArrayList<>();
        for (StorageRoute route : routes) {
            if (route.itemIds().contains(itemId) || intersects(route.itemTags(), itemTags)) {
                matches.add(route);
            }
        }
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        int highestPriority = matches.stream()
                .mapToInt(StorageRoute::priority)
                .max()
                .orElseThrow();
        List<StorageRoute> highest = matches.stream()
                .filter(route -> route.priority() == highestPriority)
                .sorted(Comparator.comparing(StorageRoute::id))
                .toList();
        if (highest.size() > 1) {
            throw new StorageRouteMatchException(
                    "ambiguous routes at priority " + highestPriority
            );
        }
        return Optional.of(highest.getFirst());
    }

    private boolean intersects(List<String> routeTags, Set<String> itemTags) {
        return routeTags.stream().anyMatch(itemTags::contains);
    }
}
