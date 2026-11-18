package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ContainerApproachTest {
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
