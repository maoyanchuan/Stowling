package dev.stowling.v2.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.v2.runtime.modules.TaskRunWorkspace;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StandingRangeSettingsTest {
    @Test
    void missingAndFalseValuesKeepTheBaselineAndOnlyIndependentClassificationCanEnableIt() {
        for (var kind : AutoClassifyDefinition.Kind.values()) {
            assertFalse(definition(kind, null).standingRangeEnabled());
            assertFalse(definition(kind, false).standingRangeEnabled());
            if (kind == AutoClassifyDefinition.Kind.CLASSIFICATION) {
                assertTrue(definition(kind, true).standingRangeEnabled());
            } else {
                assertFalse(definition(kind, true).standingRangeEnabled());
            }
        }
        var ordinary = new TaskRunWorkspace();
        ordinary.configureStandingRange(true);
        assertFalse(ordinary.standingRangeEnabled());
        var classification = new TaskRunWorkspace();
        classification.configureClassificationTask(true);
        assertFalse(classification.standingRangeEnabled());
        classification.configureStandingRange(true);
        assertTrue(classification.standingRangeEnabled());
        classification.configureStandingRange(false);
        assertFalse(classification.standingRangeEnabled());
    }

    private static AutoClassifyDefinition definition(AutoClassifyDefinition.Kind kind,
            Boolean enabled) {
        return new AutoClassifyDefinition(UUID.randomUUID(), "Sorting", false, true, true,
                "coordinate:" + UUID.randomUUID(), null, "region:" + UUID.randomUUID(),
                List.of(new AutoClassifyAreaDefinition("category-zone:" + UUID.randomUUID())),
                TaskPriority.NORMAL, false, null, null, null, null, kind, null,
                null, null, null, null, null, enabled);
    }
}
