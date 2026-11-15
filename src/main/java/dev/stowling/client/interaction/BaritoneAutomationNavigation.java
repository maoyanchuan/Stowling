package dev.stowling.client.interaction;

import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.domain.BlockPosition;
import dev.stowling.navigation.NavigationService;
import dev.stowling.schedule.StopOutcome;
import java.util.Objects;

public final class BaritoneAutomationNavigation implements NavigationPort {
    private static final double ARRIVAL_DISTANCE_SQUARED = 6.25D;
    private static final int PATH_STARTUP_GRACE_TICKS = 20;

    private final NavigationService navigationService;
    private final WorldObserver worldObserver;
    private boolean active;
    private boolean pathObserved;
    private int missingPathTicks;
    private boolean stopRequested;

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
        pathObserved = false;
        missingPathTicks = 0;
        navigationService.navigateTo(target);
        return NavigationStatus.NAVIGATING;
    }

    @Override
    public NavigationStatus status(BlockPosition target) {
        return status(target, null);
    }

    @Override
    public NavigationStatus status(BlockPosition target, Integer radius) {
        Objects.requireNonNull(target, "target");
        WorldObservation world = worldObserver.observeWorld();
        if (!world.connected() || !world.alive() || world.playerPosition().isEmpty()) {
            return NavigationStatus.FAILED;
        }
        BlockPosition current = world.playerPosition().orElseThrow();
        double deltaX = current.x() - target.x();
        double deltaY = current.y() - target.y();
        double deltaZ = current.z() - target.z();
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        if (distanceSquared <= arrivalDistanceSquared(radius)) {
            if (active) {
                active = false;
                navigationService.cancel();
            }
            return NavigationStatus.ARRIVED;
        }
        if (!active) {
            return NavigationStatus.FAILED;
        }
        if (navigationService.isNavigatingOrCalculating()) {
            pathObserved = true;
            missingPathTicks = 0;
            return NavigationStatus.NAVIGATING;
        }
        missingPathTicks++;
        if (pathObserved || missingPathTicks >= PATH_STARTUP_GRACE_TICKS) {
            active = false;
            navigationService.cancel();
            return NavigationStatus.FAILED;
        }
        return NavigationStatus.NAVIGATING;
    }

    @Override
    public boolean isInDimension(String dimension) {
        Objects.requireNonNull(dimension, "dimension");
        WorldObservation world = worldObserver.observeWorld();
        return world.connected() && dimension.equals(world.dimension());
    }

    private static double arrivalDistanceSquared(Integer radius) {
        if (radius == null) {
            return ARRIVAL_DISTANCE_SQUARED;
        }
        return (double) radius * radius;
    }

    @Override
    public void cancel() {
        if (active || navigationService.isNavigatingOrCalculating()) {
            active = false;
            navigationService.cancel();
        }
        pathObserved = false;
        missingPathTicks = 0;
    }

    @Override
    public StopOutcome requestStop() {
        stopRequested = true;
        cancel();
        return pollStop();
    }

    @Override
    public StopOutcome pollStop() {
        if (!stopRequested) {
            return StopOutcome.CLEAN;
        }
        if (navigationService.isNavigatingOrCalculating()) {
            return StopOutcome.PENDING;
        }
        stopRequested = false;
        return StopOutcome.CLEAN;
    }
}
