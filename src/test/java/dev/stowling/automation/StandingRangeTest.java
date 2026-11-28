package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StandingRangeTest {
    private static final AreaBounds FEET = new AreaBounds(220, -24, -51, 220, -24, -51);

    @ParameterizedTest
    @ValueSource(doubles = {0.0625, 0.5, 0.875, 0.9375, 1.0, 1.0625})
    void supportedSurfacesShareOneHeightBandAtPositiveAndNegativeHeights(double height) {
        for (int supportY : new int[] {-25, 64}) {
            var area = new AreaBounds(220, supportY + 1, -51, 220, supportY + 1, -51);
            assertTrue(StandingRange.settled(area, 220.5, supportY + height, -50.5,
                    true, 0, 0));
        }
    }

    @Test
    void loggedChestTopRemainsInRangeAfterLandingWhileLegacyIntegerFeetDoNot() {
        assertTrue(StandingRange.settled(FEET, 220.433, -24.125, -50.428, true, 0, 0));
        assertFalse(FEET.contains(new BlockPosition(220, -25, -51)));
        assertFalse(StandingRange.settled(FEET, 220.388, -24.0784, -50.383,
                false, 0.08, -0.08));
    }

    @Test
    void movementAirborneAndOtherFloorsCannotBeReportedAsArrived() {
        assertFalse(StandingRange.settled(FEET, 220.5, -24.125, -50.5, false, 0, 0));
        assertFalse(StandingRange.settled(FEET, 220.5, -24.125, -50.5, true, 0.1, 0));
        assertFalse(StandingRange.contains(FEET, 219.999, -24.125, -50.5));
        assertFalse(StandingRange.contains(FEET, 221, -24.125, -50.5));
        assertFalse(StandingRange.contains(FEET, 220.5, -24.125, -51.001));
        assertFalse(StandingRange.contains(FEET, 220.5, -25, -50.5));
        assertFalse(StandingRange.contains(FEET, 220.5, -23, -50.5));
    }

    @Test
    void runtimeFlagIsExplicitAndCopiesPreserveItWithoutChangingOrdinaryTargets() {
        var plain = new ContainerTarget(new BlockPosition(216, -23, -51), "minecraft:chest", FEET);
        var scoped = plain.withStandingRange(true);
        assertFalse(plain.standingRangeEnabled());
        assertTrue(scoped.withStandingArea(FEET, null).standingRangeEnabled());
        assertFalse(scoped.withStandingRange(false).standingRangeEnabled());
        assertFalse(new ContainerTarget(plain.position(), plain.type())
                .withStandingRange(true).standingRangeEnabled());
    }
}
