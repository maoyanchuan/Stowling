package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.GroundItem;
import dev.stowling.automation.NavigationStatus;
import dev.stowling.automation.NavigationTargetType;
import dev.stowling.automation.WorldObservation;
import dev.stowling.automation.WorldObserver;
import dev.stowling.domain.BlockPosition;
import dev.stowling.domain.PickupSite;
import dev.stowling.navigation.NavigationService;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BaritoneAutomationNavigationTest {
    @Test
    void usesExactBlockForGroundItemsAndKeepsWideStandardArrival() {
        FakeNavigationService navigationService = new FakeNavigationService();
        MutableWorldObserver world = new MutableWorldObserver(new BlockPosition(2, 64, 0));
        BaritoneAutomationNavigation navigation = new BaritoneAutomationNavigation(
                navigationService,
                world
        );
        BlockPosition target = new BlockPosition(0, 64, 0);

        navigation.start(target);
        assertEquals(NavigationStatus.ARRIVED, navigation.status(target));

        navigation.start(target, NavigationTargetType.GROUND_ITEM);
        assertEquals(
                NavigationStatus.NAVIGATING,
                navigation.status(target, NavigationTargetType.GROUND_ITEM)
        );
        world.setPosition(target);
        assertEquals(
                NavigationStatus.ARRIVED,
                navigation.status(target, NavigationTargetType.GROUND_ITEM)
        );
        assertEquals(
                NavigationStatus.ARRIVED,
                navigation.status(target, NavigationTargetType.GROUND_ITEM)
        );
    }

    @Test
    void reportsFailureWhenBaritoneStopsWithoutArrival() {
        FakeNavigationService navigationService = new FakeNavigationService();
        MutableWorldObserver world = new MutableWorldObserver(new BlockPosition(10, 64, 0));
        BaritoneAutomationNavigation navigation = new BaritoneAutomationNavigation(
                navigationService,
                world
        );
        BlockPosition target = new BlockPosition(0, 64, 0);

        navigation.start(target);
        assertEquals(NavigationStatus.NAVIGATING, navigation.status(target));

        navigationService.setNavigatingOrCalculating(false);

        assertEquals(NavigationStatus.FAILED, navigation.status(target));
        assertTrue(navigationService.cancelled);
    }

    @Test
    void allowsBaritoneTimeToStartPathCalculation() {
        FakeNavigationService navigationService = new FakeNavigationService();
        MutableWorldObserver world = new MutableWorldObserver(new BlockPosition(10, 64, 0));
        BaritoneAutomationNavigation navigation = new BaritoneAutomationNavigation(
                navigationService,
                world
        );
        BlockPosition target = new BlockPosition(0, 64, 0);

        navigation.start(target);
        navigationService.setNavigatingOrCalculating(false);
        for (int tick = 0; tick < 19; tick++) {
            assertEquals(NavigationStatus.NAVIGATING, navigation.status(target));
        }
        assertEquals(NavigationStatus.FAILED, navigation.status(target));
    }

    private static final class FakeNavigationService implements NavigationService {
        private boolean navigatingOrCalculating = true;
        private boolean cancelled;

        @Override
        public void navigateTo(BlockPosition target) {
            navigatingOrCalculating = true;
            cancelled = false;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isNavigatingOrCalculating() {
            return navigatingOrCalculating;
        }

        private void setNavigatingOrCalculating(boolean value) {
            navigatingOrCalculating = value;
        }
    }

    private static final class MutableWorldObserver implements WorldObserver {
        private BlockPosition position;

        private MutableWorldObserver(BlockPosition position) {
            this.position = position;
        }

        @Override
        public WorldObservation observeWorld() {
            return new WorldObservation(
                    true,
                    true,
                    "minecraft:overworld",
                    Optional.of(position)
            );
        }

        @Override
        public List<GroundItem> observeGroundItems(PickupSite site) {
            return List.of();
        }

        @Override
        public Set<String> tagsFor(String itemId) {
            return Set.of();
        }

        private void setPosition(BlockPosition position) {
            this.position = position;
        }
    }
}
