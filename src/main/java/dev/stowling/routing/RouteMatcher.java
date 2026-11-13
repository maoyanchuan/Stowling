package dev.stowling.routing;

import dev.stowling.domain.StorageRoute;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public final class RouteMatcher {
    private static final Comparator<StorageRoute> ROUTE_ID_ORDER = Comparator.comparing(
            StorageRoute::id
    );

    private final List<StorageRoute> routes;

    public RouteMatcher(List<StorageRoute> routes) {
        Objects.requireNonNull(routes, "routes");
        Set<String> ids = new HashSet<>();
        for (StorageRoute route : routes) {
            Objects.requireNonNull(route, "route");
            if (!ids.add(route.id())) {
                throw new IllegalArgumentException("duplicate storage route id: " + route.id());
            }
        }
        this.routes = List.copyOf(routes);
    }

    public RouteResolution resolve(String itemId, Set<String> itemTags) {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(itemTags, "itemTags");
        String normalizedItemId = requireText(itemId, "itemId");
        Set<String> normalizedTags = normalizeTags(itemTags);

        List<StorageRoute> exactRoutes = routes.stream()
                .filter(route -> route.itemIds().contains(normalizedItemId))
                .toList();
        if (!exactRoutes.isEmpty()) {
            return chooseHighestPriority(exactRoutes, RouteSelectorKind.ITEM_ID, normalizedItemId);
        }

        List<StorageRoute> tagRoutes = routes.stream()
                .filter(route -> matchesAnyTag(route, normalizedTags))
                .toList();
        if (tagRoutes.isEmpty()) {
            return RouteResolution.noMatch();
        }
        List<StorageRoute> highestPriority = highestPriority(tagRoutes);
        if (highestPriority.size() != 1) {
            return RouteResolution.ambiguous(sortedRouteIds(highestPriority));
        }
        StorageRoute route = highestPriority.getFirst();
        String matchedTag = route.itemTags().stream()
                .filter(normalizedTags::contains)
                .sorted()
                .findFirst()
                .orElseThrow();
        return RouteResolution.matched(new RouteMatch(route, RouteSelectorKind.TAG, matchedTag));
    }

    private static RouteResolution chooseHighestPriority(
            List<StorageRoute> candidates,
            RouteSelectorKind selectorKind,
            String selector
    ) {
        List<StorageRoute> highestPriority = highestPriority(candidates);
        if (highestPriority.size() != 1) {
            return RouteResolution.ambiguous(sortedRouteIds(highestPriority));
        }
        return RouteResolution.matched(new RouteMatch(
                highestPriority.getFirst(),
                selectorKind,
                selector
        ));
    }

    private static List<StorageRoute> highestPriority(List<StorageRoute> candidates) {
        int highestPriority = candidates.stream()
                .mapToInt(StorageRoute::priority)
                .max()
                .orElseThrow();
        return candidates.stream()
                .filter(route -> route.priority() == highestPriority)
                .sorted(ROUTE_ID_ORDER)
                .toList();
    }

    private static List<String> sortedRouteIds(List<StorageRoute> routes) {
        return routes.stream().map(StorageRoute::id).sorted().toList();
    }

    private static boolean matchesAnyTag(StorageRoute route, Set<String> itemTags) {
        return route.itemTags().stream().anyMatch(itemTags::contains);
    }

    private static Set<String> normalizeTags(Set<String> itemTags) {
        Set<String> normalized = new HashSet<>();
        for (String tag : itemTags) {
            normalized.add(requireText(tag, "item tag"));
        }
        return Set.copyOf(normalized);
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
