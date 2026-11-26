package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ContainerApproachTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyExplicitNearbyReadsCanReuseAReachablePositionOutsideTheBinding(boolean enabled) {
        var navigation = new NearbyNavigation();
        var approach = new ContainerApproach(navigation, navigation.standing,
                List.of(navigation.entrance), true, enabled);
        assertEquals(enabled ? NavigationStatus.ARRIVED : NavigationStatus.NAVIGATING,
                approach.tick());
        assertEquals(enabled ? 0 : 1, navigation.starts);
        if (enabled) assertEquals(navigation.entrance, approach.reached());
    }

    @Test
    void nearbyReadsKeepTheConfiguredStandingAreaWhenOutsideReachOrUnsafe() {
        var navigation = new NearbyNavigation();
        navigation.reachable = false;
        var approach = new ContainerApproach(navigation, navigation.standing,
                List.of(navigation.entrance), true, true);
        assertEquals(NavigationStatus.NAVIGATING, approach.tick());
        navigation.reachable = true;
        assertEquals(NavigationStatus.ARRIVED, approach.tick());
        assertEquals(1, navigation.cancels);
        assertEquals(navigation.entrance, approach.reached());
    }

    @Test
    void nearbyReadCanOpenTheReachableHalfOfADoubleChestWithoutStartingNavigation() {
        var navigation = new NearbyNavigation();
        var approach = new ContainerApproach(navigation, navigation.standing,
                List.of(new BlockPosition(100, 64, 0), navigation.entrance), true, true);
        assertEquals(NavigationStatus.ARRIVED, approach.tick());
        assertEquals(navigation.entrance, approach.reached());
        assertEquals(0, navigation.starts);
    }

    private static final class NearbyNavigation implements NavigationPort {
        private final BlockPosition entrance = new BlockPosition(1, 64, 0);
        private final AreaBounds standing = new AreaBounds(5, 64, 0, 5, 64, 0);
        private boolean reachable = true;
        private int starts;
        private int cancels;

        @Override
        public NavigationStatus start(BlockPosition target) {
            throw new AssertionError("must preserve interaction navigation");
        }

        @Override
        public NavigationStatus startInteraction(BlockPosition target, AreaBounds area,
                boolean avoidPickups) {
            assertEquals(standing, area);
            assertEquals(true, avoidPickups);
            starts++;
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public boolean isWithinInteractionRange(BlockPosition target, AreaBounds area,
                boolean avoidPickups) {
            assertEquals(true, avoidPickups);
            return reachable && area == null && entrance.equals(target);
        }

        @Override
        public NavigationStatus status(BlockPosition target) {
            return NavigationStatus.NAVIGATING;
        }

        @Override
        public void cancel() {
            cancels++;
        }
    }

    @Test
    void triesTheOtherHalfWithoutLeavingTheConfiguredStandingArea() {
        BlockPosition first = new BlockPosition(68, -35, 29);
        BlockPosition second = new BlockPosition(68, -35, 30);
        AreaBounds standing = new AreaBounds(65, -39, 34, 68, -38, 34);
        List<BlockPosition> starts = new ArrayList<>();
        NavigationPort navigation = new NavigationPort() {
            private boolean moved;

            @Override
            public NavigationStatus start(BlockPosition target) {
                throw new AssertionError("must use the configured standing area");
            }

            @Override
            public NavigationStatus startInteraction(BlockPosition target, AreaBounds area) {
                assertEquals(standing, area);
                starts.add(target);
                return target.equals(first) ? NavigationStatus.FAILED : NavigationStatus.NAVIGATING;
            }

            @Override
            public NavigationStatus status(BlockPosition target) {
                moved = true;
                return NavigationStatus.ARRIVED;
            }

            @Override
            public boolean isWithinInteractionRange(BlockPosition target, AreaBounds area) {
                return moved && target.equals(second) && area.equals(standing);
            }

            @Override
            public void cancel() {
            }
        };
        ContainerApproach approach = new ContainerApproach(navigation, standing,
                List.of(first, second));

        assertEquals(NavigationStatus.NAVIGATING, approach.tick());
        assertEquals(NavigationStatus.ARRIVED, approach.tick());
        assertEquals(second, approach.reached());
        assertEquals(List.of(first, second), starts);
    }
}
