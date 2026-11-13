package dev.stowling.client.interaction;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static final class FakeNavigationService implements NavigationService {
        @Override
        public void navigateTo(BlockPosition target) {
        }

        @Override
        public void cancel() {
        }

        @Override
        public boolean isNavigating() {
            return true;
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
