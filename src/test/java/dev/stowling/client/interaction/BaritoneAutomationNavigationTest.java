package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.WorldObservation;
import dev.stowling.domain.BlockPosition;
import dev.stowling.navigation.NavigationService;
import dev.stowling.v2.model.AreaBounds;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class BaritoneAutomationNavigationTest {
    @Test
    void configuredStandingAreaRequiresBothCorrectFeetPositionAndRealReach() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        service.withinInteractionRange = true;
        AtomicReference<BlockPosition> player = new AtomicReference<>(new BlockPosition(0, 64, 0));
        BaritoneAutomationNavigation navigation = navigationAt(service, player);
        BlockPosition chest = new BlockPosition(0, 68, 0);
        AreaBounds area = new AreaBounds(1, 64, 0, 2, 64, 0);

        navigation.startInteraction(chest, area);
        assertEquals(area, service.standingArea);
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(chest, 4));
        player.set(new BlockPosition(1, 64, 0));
        service.withinInteractionRange = false;
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(chest, 4));
        service.withinInteractionRange = true;
        assertEquals(NavigationStatus.ARRIVED, navigation.status(chest, 4));
        assertEquals(0, service.nearCalls);
    }

    @Test
    void standingAreaIsRetainedWhenNavigationRetries() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        BaritoneAutomationNavigation navigation = navigationAt(service,
                new AtomicReference<>(new BlockPosition(0, 64, 0)));
        BlockPosition chest = new BlockPosition(0, 68, 0);
        AreaBounds area = new AreaBounds(1, 64, 0, 2, 64, 0);
        navigation.startInteraction(chest, area);
        navigation.status(chest, 4);
        service.navigating = false;
        navigation.status(chest, 4);
        for (int tick = 0; tick < 20; tick++) {
            navigation.status(chest, 4);
        }

        assertEquals(2, service.interactionCalls);
        assertEquals(area, service.standingArea);
        assertEquals(0, service.nearCalls);
    }

    @Test
    void interactionWithoutStandingAreaDoesNotUseRadiusGoal() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        BaritoneAutomationNavigation navigation = navigationAt(service,
                new AtomicReference<>(new BlockPosition(6, -45, 34)));
        BlockPosition chest = new BlockPosition(5, -43, 32);

        assertEquals(NavigationStatus.NAVIGATING, navigation.startInteraction(chest, null));
        assertEquals(1, service.interactionCalls);
        assertEquals(0, service.nearCalls);
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(chest));
        service.withinInteractionRange = true;
        assertEquals(NavigationStatus.ARRIVED, navigation.status(chest));
    }

    @Test
    void startNearUsesNearNavigationWithoutExactGoal() {
        RecordingNavigationService service = new RecordingNavigationService();
        BaritoneAutomationNavigation navigation = new BaritoneAutomationNavigation(
                service,
                WorldObservation::disconnected
        );
        BlockPosition target = new BlockPosition(7, -40, 1);

        NavigationStatus status = navigation.startNear(target, 2);

        assertEquals(NavigationStatus.NAVIGATING, status);
        assertEquals(target, service.nearTarget);
        assertEquals(2, service.nearRadius);
        assertEquals(1, service.nearCalls);
        assertNull(service.exactTarget);
        assertNull(service.coordinateTarget);
    }

    @Test
    void arrivesImmediatelyWhenTheTargetIsWithinInteractionRange() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.withinInteractionRange = true;
        BaritoneAutomationNavigation navigation = navigationAt(
                service,
                new AtomicReference<>(new BlockPosition(100, 64, 100))
        );
        BlockPosition target = new BlockPosition(7, 67, 1);

        assertEquals(NavigationStatus.NAVIGATING, navigation.startNear(target, 4));
        NavigationStatus status = navigation.status(target, 4);

        assertEquals(NavigationStatus.ARRIVED, status);
        assertEquals(1, service.interactionChecks);
        assertEquals(1, service.cancellations);
        assertEquals(target, service.nearTarget);
        assertEquals(4, service.nearRadius);
        assertNull(service.exactTarget);
        assertNull(service.coordinateTarget);
    }

    @Test
    void exactNavigationDoesNotStopAtBlockInteractionRange() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.withinInteractionRange = true;
        service.navigating = true;
        BaritoneAutomationNavigation navigation = navigationAt(
                service,
                new AtomicReference<>(new BlockPosition(7, 64, 5))
        );
        BlockPosition target = new BlockPosition(7, 64, 1);

        assertEquals(NavigationStatus.NAVIGATING, navigation.start(target));
        NavigationStatus status = navigation.status(target, 1);

        assertEquals(NavigationStatus.NAVIGATING, status);
        assertEquals(target, service.exactTarget);
        assertEquals(0, service.interactionChecks);
        assertEquals(0, service.cancellations);
        assertNull(service.nearTarget);
        assertNull(service.coordinateTarget);
    }

    @Test
    void exactNavigationWaitsForTheSameBlockByDefault() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        AtomicReference<BlockPosition> player =
                new AtomicReference<>(new BlockPosition(8, 64, 1));
        BaritoneAutomationNavigation navigation = navigationAt(service, player);
        BlockPosition target = new BlockPosition(7, 64, 1);

        assertEquals(NavigationStatus.NAVIGATING, navigation.start(target));
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target));
        player.set(new BlockPosition(9, 64, 1));
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target));
        assertEquals(0, service.cancellations);
        player.set(target);
        assertEquals(NavigationStatus.ARRIVED, navigation.status(target));
        assertEquals(1, service.cancellations);
    }

    @Test
    void startWithinRadiusUsesTheCoordinateEntryInsteadOfContainerNear() {
        RecordingNavigationService service = new RecordingNavigationService();
        BaritoneAutomationNavigation navigation = navigationAt(
                service,
                new AtomicReference<>(new BlockPosition(100, 64, 100))
        );
        BlockPosition target = new BlockPosition(7, 64, 1);

        NavigationStatus status = navigation.startWithinRadius(target, 2);

        assertEquals(NavigationStatus.NAVIGATING, status);
        assertEquals(target, service.coordinateTarget);
        assertEquals(2, service.coordinateRadius);
        assertNull(service.exactTarget);
        assertNull(service.nearTarget);
    }

    @Test
    void coordinateRadiusZeroIgnoresBlockInteractionRange() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        service.withinInteractionRange = true;
        AtomicReference<BlockPosition> player =
                new AtomicReference<>(new BlockPosition(8, 64, 1));
        BaritoneAutomationNavigation navigation = navigationAt(service, player);
        BlockPosition target = new BlockPosition(7, 64, 1);

        navigation.startWithinRadius(target, 0);
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target));
        assertEquals(0, service.interactionChecks);
        player.set(target);
        assertEquals(NavigationStatus.ARRIVED, navigation.status(target));
        assertEquals(1, service.cancellations);
    }

    @Test
    void coordinateRadiusOneUsesThreeDimensionalDistance() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        service.withinInteractionRange = true;
        AtomicReference<BlockPosition> player =
                new AtomicReference<>(new BlockPosition(8, 65, 1));
        BaritoneAutomationNavigation navigation = navigationAt(service, player);
        BlockPosition target = new BlockPosition(7, 64, 1);

        navigation.startWithinRadius(target, 1);
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target));
        player.set(new BlockPosition(7, 66, 1));
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target));
        player.set(new BlockPosition(8, 64, 1));
        assertEquals(NavigationStatus.ARRIVED, navigation.status(target));
    }

    @Test
    void statusWithoutExplicitRadiusUsesTheStartedCoordinateRadius() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        BaritoneAutomationNavigation navigation = navigationAt(
                service,
                new AtomicReference<>(new BlockPosition(9, 64, 1))
        );
        BlockPosition target = new BlockPosition(7, 64, 1);

        navigation.startWithinRadius(target, 2);
        NavigationStatus status = navigation.status(target);

        assertEquals(NavigationStatus.ARRIVED, status);
        assertEquals(1, service.cancellations);
    }

    @Test
    void coordinatePathLostOutsideRadiusFailsInsteadOfArriving() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        BaritoneAutomationNavigation navigation = navigationAt(
                service,
                new AtomicReference<>(new BlockPosition(9, 64, 1))
        );
        BlockPosition target = new BlockPosition(7, 64, 1);

        navigation.startWithinRadius(target, 1);
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target));
        service.navigating = false;
        assertEquals(NavigationStatus.FAILED, navigation.status(target));
        assertEquals(1, service.cancellations);
    }

    @Test
    void explicitStatusRadiusKeepsLegacyDistanceSemantics() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        BaritoneAutomationNavigation navigation = navigationAt(
                service,
                new AtomicReference<>(new BlockPosition(7, 64, 5))
        );
        BlockPosition target = new BlockPosition(7, 64, 1);

        navigation.start(target);
        NavigationStatus status = navigation.status(target, 4);

        assertEquals(NavigationStatus.ARRIVED, status);
    }

    @Test
    void nearNavigationRequiresInteractionRangeInsteadOfRadiusAlone() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        BaritoneAutomationNavigation navigation = navigationAt(
                service,
                new AtomicReference<>(new BlockPosition(10, 64, 1))
        );
        BlockPosition target = new BlockPosition(7, 64, 1);

        navigation.startNear(target, 4);
        NavigationStatus status = navigation.status(target, 4);

        assertEquals(NavigationStatus.NAVIGATING, status);
    }

    @Test
    void retriesNearNavigationBeforeReportingFailure() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.navigating = true;
        BaritoneAutomationNavigation navigation = navigationAt(
                service,
                new AtomicReference<>(new BlockPosition(100, 64, 100))
        );
        BlockPosition target = new BlockPosition(7, -40, 1);

        navigation.startNear(target, 4);
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target, 4));
        service.navigating = false;
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target, 4));
        for (int tick = 0; tick < 20; tick++) {
            navigation.status(target, 4);
        }
        assertEquals(2, service.nearCalls);

        service.navigating = true;
        navigation.status(target, 4);
        service.navigating = false;
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target, 4));
        for (int tick = 0; tick < 20; tick++) {
            navigation.status(target, 4);
        }
        assertEquals(3, service.nearCalls);

        service.navigating = true;
        navigation.status(target, 4);
        service.navigating = false;
        assertEquals(NavigationStatus.FAILED, navigation.status(target, 4));
    }

    private static BaritoneAutomationNavigation navigationAt(
            RecordingNavigationService service,
            AtomicReference<BlockPosition> player
    ) {
        return new BaritoneAutomationNavigation(
                service,
                () -> new WorldObservation(
                        true,
                        true,
                        "minecraft:overworld",
                        Optional.of(player.get())
                )
        );
    }

    private static final class RecordingNavigationService
            implements NavigationService {
        private BlockPosition exactTarget;
        private BlockPosition nearTarget;
        private BlockPosition coordinateTarget;
        private int nearRadius;
        private int coordinateRadius;
        private int nearCalls;
        private int interactionChecks;
        private int cancellations;
        private boolean withinInteractionRange;
        private boolean navigating;
        private AreaBounds standingArea;
        private int interactionCalls;

        @Override
        public void navigateToInteractionRange(BlockPosition target, AreaBounds area) {
            standingArea = area;
            interactionCalls++;
        }

        @Override
        public void navigateTo(BlockPosition target) {
            exactTarget = target;
        }

        @Override
        public void navigateToNear(BlockPosition target, int radius) {
            nearTarget = target;
            nearRadius = radius;
            nearCalls++;
        }

        @Override
        public void navigateToWithinRadius(BlockPosition target, int radius) {
            coordinateTarget = target;
            coordinateRadius = radius;
        }

        @Override
        public boolean isWithinInteractionRange(BlockPosition target) {
            interactionChecks++;
            return withinInteractionRange;
        }

        @Override
        public void cancel() {
            cancellations++;
        }

        @Override
        public boolean isNavigatingOrCalculating() {
            return navigating;
        }
    }
}
