package dev.stowling.client.interaction;

import dev.stowling.Stowling;
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
    private static final int NEAR_PATH_RETRIES = 2;
    private static final int NEAR_RETRY_DELAY_TICKS = 20;

    private final NavigationService navigationService;
    private final WorldObserver worldObserver;
    private boolean active;
    private boolean nearNavigation;
    private boolean pathObserved;
    private int missingPathTicks;
    private int nearPathRetries;
    private int nearRadius;
    private int retryDelayTicks;
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
        nearNavigation = false;
        nearPathRetries = 0;
        retryDelayTicks = 0;
        prepareNavigation();
        navigationService.navigateTo(target);
        return NavigationStatus.NAVIGATING;
    }

    @Override
    public NavigationStatus startNear(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        nearNavigation = true;
        nearPathRetries = 0;
        nearRadius = radius;
        retryDelayTicks = 0;
        prepareNavigation();
        navigationService.navigateToNear(target, radius);
        return NavigationStatus.NAVIGATING;
    }

    private void prepareNavigation() {
        active = true;
        pathObserved = false;
        missingPathTicks = 0;
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
        if (nearNavigation
                && navigationService.isWithinInteractionRange(target)) {
            return arrive();
        }
        BlockPosition current = world.playerPosition().orElseThrow();
        double deltaX = current.x() - target.x();
        double deltaY = current.y() - target.y();
        double deltaZ = current.z() - target.z();
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        if (!nearNavigation
                && distanceSquared <= arrivalDistanceSquared(radius)) {
            return arrive();
        }
        if (!active) {
            return NavigationStatus.FAILED;
        }
        if (retryDelayTicks > 0) {
            retryDelayTicks--;
            if (retryDelayTicks == 0) {
                navigationService.navigateToNear(
                        target,
                        radius == null ? nearRadius : radius
                );
            }
            return NavigationStatus.NAVIGATING;
        }
        if (navigationService.isNavigatingOrCalculating()) {
            pathObserved = true;
            missingPathTicks = 0;
            return NavigationStatus.NAVIGATING;
        }
        missingPathTicks++;
        if (pathObserved || missingPathTicks >= PATH_STARTUP_GRACE_TICKS) {
            return retryNearOrFail(target, radius);
        }
        return NavigationStatus.NAVIGATING;
    }

    private NavigationStatus arrive() {
        if (active) {
            active = false;
            navigationService.cancel();
        }
        nearNavigation = false;
        nearPathRetries = 0;
        retryDelayTicks = 0;
        return NavigationStatus.ARRIVED;
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
        nearNavigation = false;
        nearPathRetries = 0;
        retryDelayTicks = 0;
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

    private NavigationStatus retryNearOrFail(
            BlockPosition target,
            Integer radius
    ) {
        if (nearNavigation && nearPathRetries < NEAR_PATH_RETRIES) {
            nearPathRetries++;
            Stowling.LOGGER.warn(
                    "Near navigation failed; retrying {} of {} for {}",
                    nearPathRetries,
                    NEAR_PATH_RETRIES,
                    target
            );
            prepareNavigation();
            retryDelayTicks = NEAR_RETRY_DELAY_TICKS;
            return NavigationStatus.NAVIGATING;
        }
        active = false;
        nearNavigation = false;
        nearPathRetries = 0;
        retryDelayTicks = 0;
        navigationService.cancel();
        return NavigationStatus.FAILED;
    }
}
