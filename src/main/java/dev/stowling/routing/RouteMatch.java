package dev.stowling.routing;

import dev.stowling.domain.StorageRoute;
import java.util.Objects;

public record RouteMatch(
        StorageRoute route,
        RouteSelectorKind selectorKind,
        String matchedSelector
) {
    public RouteMatch {
        route = Objects.requireNonNull(route, "route");
        selectorKind = Objects.requireNonNull(selectorKind, "selectorKind");
        matchedSelector = Objects.requireNonNull(matchedSelector, "matchedSelector");
        if (matchedSelector.isBlank()) {
            throw new IllegalArgumentException("matchedSelector must not be blank");
        }
    }
}
