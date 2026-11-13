package dev.stowling.routing;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record RouteResolution(
        RouteResolutionStatus status,
        Optional<RouteMatch> match,
        List<String> candidateRouteIds
) {
    public RouteResolution {
        status = Objects.requireNonNull(status, "status");
        match = Objects.requireNonNull(match, "match");
        candidateRouteIds = List.copyOf(
                Objects.requireNonNull(candidateRouteIds, "candidateRouteIds")
        );
        if (status == RouteResolutionStatus.MATCHED && (match.isEmpty()
                || candidateRouteIds.size() != 1)) {
            throw new IllegalArgumentException("matched resolution must have one candidate");
        }
        if (status == RouteResolutionStatus.AMBIGUOUS && (match.isPresent()
                || candidateRouteIds.size() < 2)) {
            throw new IllegalArgumentException("ambiguous resolution must have two candidates");
        }
        if (status == RouteResolutionStatus.NO_MATCH
                && (match.isPresent() || !candidateRouteIds.isEmpty())) {
            throw new IllegalArgumentException("no-match resolution cannot have candidates");
        }
    }

    public static RouteResolution matched(RouteMatch match) {
        Objects.requireNonNull(match, "match");
        return new RouteResolution(
                RouteResolutionStatus.MATCHED,
                Optional.of(match),
                List.of(match.route().id())
        );
    }

    public static RouteResolution ambiguous(List<String> candidateRouteIds) {
        return new RouteResolution(
                RouteResolutionStatus.AMBIGUOUS,
                Optional.empty(),
                candidateRouteIds
        );
    }

    public static RouteResolution noMatch() {
        return new RouteResolution(
                RouteResolutionStatus.NO_MATCH,
                Optional.empty(),
                List.of()
        );
    }
}
