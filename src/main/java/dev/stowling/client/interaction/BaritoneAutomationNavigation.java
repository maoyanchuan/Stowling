package dev.stowling.client.interaction;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.NavigationTargetType;
import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.domain.BlockPosition;
import dev.stowling.navigation.NavigationService;
import java.util.Objects;

public final class BaritoneAutomationNavigation implements NavigationPort {
    private static final double STANDARD_ARRIVAL_DISTANCE_SQUARED = 6.25D;
    private static final double GROUND_ITEM_ARRIVAL_DISTANCE_SQUARED = 0.0D;

    private final NavigationService navigationService;
    private final WorldObserver worldObserver;
    private boolean active;

    public BaritoneAutomationNavigation(
            NavigationService navigationService,
            WorldObserver worldObserver
    ) {
        this.navigationService = Objects.requireNonNull(
                navigationService,
                "navigationService"
        );
        this.worldObserver = Objects.requireNonNull(worldObserver, "worldObserver");
    }

    @Override
    public NavigationStatus start(BlockPosition target) {
        return start(target, NavigationTargetType.STANDARD);
    }

    @Override
    public NavigationStatus start(BlockPosition target, NavigationTargetType targetType) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetType, "targetType");
        active = true;
        navigationService.navigateTo(target);
        return NavigationStatus.NAVIGATING;
    }

    @Override
    public NavigationStatus status(BlockPosition target) {
        return status(target, NavigationTargetType.STANDARD);
    }

    @Override
    public NavigationStatus status(BlockPosition target, NavigationTargetType targetType) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(targetType, "targetType");
        WorldObservation world = worldObserver.observeWorld();
        if (!world.connected() || !world.alive() || world.playerPosition().isEmpty()) {
            return NavigationStatus.FAILED;
        }
        BlockPosition current = world.playerPosition().orElseThrow();
        double deltaX = current.x() - target.x();
        double deltaY = current.y() - target.y();
        double deltaZ = current.z() - target.z();
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        if (distanceSquared <= arrivalDistanceSquared(targetType)) {
            if (active) {
                active = false;
                navigationService.cancel();
            }
            return NavigationStatus.ARRIVED;
        }
        if (!active) {
            return NavigationStatus.FAILED;
        }
        return NavigationStatus.NAVIGATING;
    }

    private static double arrivalDistanceSquared(NavigationTargetType targetType) {
        if (targetType == NavigationTargetType.GROUND_ITEM) {
            return GROUND_ITEM_ARRIVAL_DISTANCE_SQUARED;
        }
        return STANDARD_ARRIVAL_DISTANCE_SQUARED;
    }

    @Override
    public void cancel() {
        if (active) {
            active = false;
            navigationService.cancel();
        }
    }
}
