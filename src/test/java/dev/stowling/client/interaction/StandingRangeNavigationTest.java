package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.WorldObservation;
import dev.stowling.domain.BlockPosition;
import dev.stowling.navigation.NavigationService;
import dev.stowling.v2.model.AreaBounds;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StandingRangeNavigationTest {
    private static final AreaBounds FEET = new AreaBounds(220, -24, -51, 220, -24, -51);
    private static final BlockPosition TARGET = new BlockPosition(216, -23, -51);

    @Test
    void chestTopCanRealignWithoutExcludingItsOnlyStanceAndOtherCallsStayStrict() {
        var service = new Service();
        var navigation = navigation(service);
        assertEquals(NavigationStatus.NAVIGATING,
                navigation.startInteraction(TARGET, FEET, true, true));
        assertEquals(1, service.rangePaths);
        assertTrue(service.rejected.isEmpty());
        assertFalse(navigation.isWithinInteractionRange(TARGET, FEET, true, true));
        service.settled = true;
        assertEquals(NavigationStatus.ARRIVED, navigation.status(TARGET));
        assertFalse(navigation.isWithinInteractionRange(TARGET, FEET, true, false));
        assertEquals(NavigationStatus.NAVIGATING,
                navigation.startInteraction(TARGET, FEET, true, false));
        assertEquals(1, service.strictPaths);
        assertEquals(Set.of(new BlockPosition(220, -24, -51)), service.rejected);
    }

    @Test
    void settledStandingStillRequiresRealReachAndPickupSafety() {
        var service = new Service();
        var navigation = navigation(service);
        service.settled = true;
        service.reachable = false;
        assertFalse(navigation.isWithinInteractionRange(TARGET, FEET, true, true));
        service.reachable = true;
        service.hazard = true;
        assertFalse(navigation.isWithinInteractionRange(TARGET, FEET, true, true));
        service.hazard = false;
        assertTrue(navigation.isWithinInteractionRange(TARGET, FEET, true, true));
    }

    @Test
    void waitingForLandingIsBoundedByTheExistingRetryLimit() {
        var service = new Service();
        var navigation = navigation(service);
        navigation.startInteraction(TARGET, FEET, true, true);
        NavigationStatus status = NavigationStatus.NAVIGATING;
        for (int tick = 0; tick < 150 && status == NavigationStatus.NAVIGATING; tick++) {
            status = navigation.status(TARGET);
        }
        assertEquals(NavigationStatus.FAILED, status);
        assertEquals(3, service.rangePaths);
        assertTrue(service.rejected.isEmpty());
    }

    private static BaritoneAutomationNavigation navigation(Service service) {
        return new BaritoneAutomationNavigation(service, () -> new WorldObservation(true, true,
                "minecraft:overworld", Optional.of(new BlockPosition(220, -25, -51))));
    }

    private static final class Service implements NavigationService {
        private boolean settled;
        private boolean reachable = true;
        private boolean hazard;
        private int rangePaths;
        private int strictPaths;
        private Set<BlockPosition> rejected = Set.of();

        @Override
        public boolean isWithinStandingRange(AreaBounds area) {
            assertEquals(FEET, area);
            return settled;
        }

        @Override
        public boolean isWithinInteractionRange(BlockPosition target, boolean avoidPickups) {
            return reachable && (!avoidPickups || !hazard);
        }

        @Override
        public boolean navigateToStandingRange(BlockPosition target, AreaBounds area,
                Set<BlockPosition> excluded, boolean avoidPickups) {
            assertEquals(FEET, area);
            assertTrue(avoidPickups);
            rangePaths++;
            rejected = Set.copyOf(excluded);
            return true;
        }

        @Override
        public boolean navigateToInteractionRange(BlockPosition target, AreaBounds area,
                Set<BlockPosition> excluded, boolean avoidPickups) {
            strictPaths++;
            rejected = Set.copyOf(excluded);
            return true;
        }

        @Override
        public Optional<BlockPosition> interactionFeet() {
            return Optional.of(new BlockPosition(220, -24, -51));
        }

        @Override
        public void navigateTo(BlockPosition target) {
        }

        @Override
        public void navigateToNear(BlockPosition target, int radius) {
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isNavigatingOrCalculating() {
            return false;
        }
    }
}
