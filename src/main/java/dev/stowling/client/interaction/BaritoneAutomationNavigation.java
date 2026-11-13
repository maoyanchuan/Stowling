package dev.stowling.client.interaction;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.domain.BlockPosition;
import dev.stowling.navigation.NavigationService;
import java.util.Objects;

public final class BaritoneAutomationNavigation implements NavigationPort {
    private static final double ARRIVAL_DISTANCE_SQUARED = 6.25D;

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
        Objects.requireNonNull(target, "target");
        active = true;
        navigationService.navigateTo(target);
        return NavigationStatus.NAVIGATING;
    }

    @Override
    public NavigationStatus status(BlockPosition target) {
        Objects.requireNonNull(target, "target");
        if (!active) {
            return NavigationStatus.FAILED;
        }
        WorldObservation world = worldObserver.observeWorld();
        if (!world.connected() || !world.alive() || world.playerPosition().isEmpty()) {
            return NavigationStatus.FAILED;
        }
        BlockPosition current = world.playerPosition().orElseThrow();
        double deltaX = current.x() - target.x();
        double deltaY = current.y() - target.y();
        double deltaZ = current.z() - target.z();
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        if (distanceSquared <= ARRIVAL_DISTANCE_SQUARED) {
            active = false;
            navigationService.cancel();
            return NavigationStatus.ARRIVED;
        }
        return NavigationStatus.NAVIGATING;
    }

    @Override
    public void cancel() {
        if (active) {
            active = false;
            navigationService.cancel();
        }
    }
}
