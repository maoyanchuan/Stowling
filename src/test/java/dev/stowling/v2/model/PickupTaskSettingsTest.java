package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.stowling.v2.config.ConfigValidator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PickupTaskSettingsTest {
    @Test
    void defaultsWaitThirtySecondsAndDetectionUsesTheConfiguredInterval() {
        var defaults = definition(null, null);
        assertEquals(1, defaults.detectionIntervalSeconds());
        assertEquals(30, defaults.followUpDelaySeconds());
        assertEquals("30", AutoClassifyTaskFactory.tasks(defaults, List.of()).getFirst()
                .steps().getLast().parameters().get(ConfigValidator.CREATE_DELAY_SECONDS));

        var custom = definition(7, 45);
        assertEquals(140, AutoClassifyTaskFactory.triggers(custom, List.of()).getFirst()
                .intervalTicks());
        assertEquals("45", AutoClassifyTaskFactory.tasks(custom, List.of()).getFirst()
                .steps().getLast().parameters().get(ConfigValidator.CREATE_DELAY_SECONDS));
    }

    @Test
    void rejectsInvalidIntervalsAndCountdowns() {
        assertThrows(IllegalArgumentException.class, () -> definition(0, 30));
        assertThrows(IllegalArgumentException.class, () -> definition(61, 30));
        assertThrows(IllegalArgumentException.class, () -> definition(1, -1));
        assertThrows(IllegalArgumentException.class, () -> definition(1, 86401));
        assertEquals(0, definition(1, 0).followUpDelaySeconds());
    }

    private static AutoClassifyDefinition definition(Integer interval, Integer delay) {
        return new AutoClassifyDefinition(UUID.randomUUID(), "Pickup", true, true, false,
                "coordinate:" + UUID.randomUUID(), 8, "coordinate:" + UUID.randomUUID(),
                List.of(), TaskPriority.NORMAL, false, null, null, null, null,
                AutoClassifyDefinition.Kind.PICKUP, "task:" + UUID.randomUUID(), interval, delay);
    }
}
