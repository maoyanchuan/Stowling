package dev.stowling.client.interaction;

import dev.stowling.Stowling;
import dev.stowling.automation.NavigationPort;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.domain.BlockPosition;
import dev.stowling.navigation.NavigationService;
import dev.stowling.schedule.StopOutcome;
import dev.stowling.v2.model.AreaBounds;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class BaritoneAutomationNavigation implements NavigationPort {
    private static final int DEFAULT_COORDINATE_RADIUS = 0;
    private static final int PATH_STARTUP_GRACE_TICKS = 20;
    private static final int NEAR_PATH_RETRIES = 2;
    private static final int NEAR_RETRY_DELAY_TICKS = 20;

    private final NavigationService navigationService;
    private final WorldObserver worldObserver;
    private final Set<BlockPosition> rejectedStances = new HashSet<>();
    private boolean active;
    private boolean nearNavigation;
    private boolean horizontalNavigation;
    private boolean pathObserved;
    private int coordinateRadius = DEFAULT_COORDINATE_RADIUS;
    private int missingPathTicks;
    private int nearPathRetries;
    private Integer nearRadius;
    private int retryDelayTicks;
    private boolean stopRequested;
    private AreaBounds standingArea;

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
        coordinateRadius = DEFAULT_COORDINATE_RADIUS;
        nearNavigation = false;
        standingArea = null;
        nearPathRetries = 0;
        retryDelayTicks = 0;
        prepareNavigation();
        navigationService.navigateTo(target);
        return NavigationStatus.NAVIGATING;
    }

    @Override
    public NavigationStatus startHorizontal(BlockPosition target) {
        Objects.requireNonNull(target, "target");
        coordinateRadius = DEFAULT_COORDINATE_RADIUS;
        nearNavigation = false;
        standingArea = null;
        nearPathRetries = 0;
        retryDelayTicks = 0;
        prepareNavigation();
        horizontalNavigation = true;
        navigationService.navigateToHorizontal(target);
        return NavigationStatus.NAVIGATING;
    }

    @Override
    public NavigationStatus startWithinRadius(BlockPosition target, int radius) {
        Objects.requireNonNull(target, "target");
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        coordinateRadius = radius;
        nearNavigation = false;
        standingArea = null;
        nearPathRetries = 0;
        retryDelayTicks = 0;
        prepareNavigation();
        navigationService.navigateToWithinRadius(target, radius);
        return NavigationStatus.NAVIGATING;
    }

    @Override
    public NavigationStatus startNear(BlockPosition target, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("radius must not be negative");
        }
        return beginNear(target, radius, null);
    }

    @Override
    public NavigationStatus startInteraction(BlockPosition target, AreaBounds standingArea) {
        if (isWithinInteractionRange(target, standingArea)) {
            return arrive();
        }
        return beginNear(target, null, standingArea);
    }

    private NavigationStatus beginNear(BlockPosition target, Integer radius, AreaBounds area) {
        Objects.requireNonNull(target, "target");
        nearNavigation = true;
        standingArea = area;
        nearPathRetries = 0;
        nearRadius = radius;
        retryDelayTicks = 0;
        rejectedStances.clear();
        prepareNavigation();
        return navigateNear(target, radius);
    }

    private NavigationStatus navigateNear(BlockPosition target, Integer radius) {
        if (nearRadius == null) {
            navigationService.interactionFeet().ifPresent(rejectedStances::add);
            if (!navigationService.navigateToInteractionRange(target, standingArea,
                    rejectedStances)) {
                return failNavigation();
            }
        } else {
            navigationService.navigateToNear(target, radius == null ? nearRadius : radius);
        }
        return NavigationStatus.NAVIGATING;
    }

    private void prepareNavigation() {
        horizontalNavigation = false;
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
                && isWithinInteractionRange(target, standingArea)) {
            return arrive();
        }
        BlockPosition current = world.playerPosition().orElseThrow();
        double deltaX = current.x() - target.x();
        double deltaY = horizontalNavigation ? 0 : current.y() - target.y();
        double deltaZ = current.z() - target.z();
        double distanceSquared = deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
        int targetRadius = radius == null ? coordinateRadius : radius;
        if (!nearNavigation && distanceSquared <= arrivalDistanceSquared(targetRadius)) {
            return arrive();
        }
        if (!active) {
            return NavigationStatus.FAILED;
        }
        if (retryDelayTicks > 0) {
            retryDelayTicks--;
            if (retryDelayTicks == 0) {
                return navigateNear(target, radius);
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
            return retryNearOrFail(target, current);
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

    @Override
    public boolean isWithinInteractionRange(BlockPosition target) {
        Objects.requireNonNull(target, "target");
        return navigationService.isWithinInteractionRange(target);
    }

    @Override
    public boolean isWithinInteractionRange(BlockPosition target, AreaBounds area) {
        if (area != null && worldObserver.observeWorld().playerPosition()
                .filter(area::contains).isEmpty()) {
            return false;
        }
        return isWithinInteractionRange(target);
    }

    private static double arrivalDistanceSquared(int radius) {
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
        standingArea = null;
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

    private NavigationStatus retryNearOrFail(BlockPosition target, BlockPosition current) {
        if (nearNavigation && nearPathRetries < NEAR_PATH_RETRIES) {
            nearPathRetries++;
            Stowling.LOGGER.warn(
                    "Near navigation failed; retrying {} of {} from {} to {}",
                    nearPathRetries,
                    NEAR_PATH_RETRIES,
                    current,
                    target
            );
            prepareNavigation();
            retryDelayTicks = NEAR_RETRY_DELAY_TICKS;
            return NavigationStatus.NAVIGATING;
        }
        return failNavigation();
    }

    private NavigationStatus failNavigation() {
        active = false;
        nearNavigation = false;
        nearPathRetries = 0;
        retryDelayTicks = 0;
        navigationService.cancel();
        return NavigationStatus.FAILED;
    }
}
