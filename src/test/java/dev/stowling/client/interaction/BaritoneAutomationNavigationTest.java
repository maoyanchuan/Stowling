package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.WorldObservation;
import dev.stowling.domain.BlockPosition;
import dev.stowling.navigation.NavigationService;
import org.junit.jupiter.api.Test;

class BaritoneAutomationNavigationTest {
    @Test
    void startNearUsesNearNavigationWithoutExactGoal() {
        RecordingNavigationService service = new RecordingNavigationService();
        BaritoneAutomationNavigation navigation =
                new BaritoneAutomationNavigation(
                        service,
                        WorldObservation::disconnected
                );
        BlockPosition target = new BlockPosition(7, -40, 1);

        NavigationStatus status = navigation.startNear(target, 2);

        assertEquals(NavigationStatus.NAVIGATING, status);
        assertEquals(target, service.nearTarget);
        assertEquals(2, service.nearRadius);
        assertNull(service.exactTarget);
    }

    @Test
    void arrivesImmediatelyWhenTheTargetIsWithinInteractionRange() {
        RecordingNavigationService service = new RecordingNavigationService();
        service.withinInteractionRange = true;
        BaritoneAutomationNavigation navigation =
                new BaritoneAutomationNavigation(
                        service,
                        () -> new WorldObservation(
                                true,
                                true,
                                "minecraft:overworld",
                                Optional.of(new BlockPosition(100, 64, 100))
                        )
                );
        BlockPosition target = new BlockPosition(7, 67, 1);

        navigation.startNear(target, 4);
        NavigationStatus status = navigation.status(target, 4);

        assertEquals(NavigationStatus.ARRIVED, status);
    }

    private static final class RecordingNavigationService
            implements NavigationService {
        private BlockPosition exactTarget;
        private BlockPosition nearTarget;
        private int nearRadius;
        private boolean withinInteractionRange;

        @Override
        public void navigateTo(BlockPosition target) {
            exactTarget = target;
        }

        @Override
        public void navigateToNear(BlockPosition target, int radius) {
            nearTarget = target;
            nearRadius = radius;
        }

        @Override
        public boolean isWithinInteractionRange(BlockPosition target) {
            return withinInteractionRange;
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
