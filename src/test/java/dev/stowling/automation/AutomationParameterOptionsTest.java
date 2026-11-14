package dev.stowling.automation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.stowling.automation.AutomationModuleDescriptor.Choice;
import dev.stowling.automation.AutomationModuleDescriptor.Parameter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AutomationParameterOptionsTest {
    @Test
    void choicesAreValidatedAndLegacyParametersKeepEmptyOptions() {
        Parameter legacy = new Parameter("count", "Count", "positive-integer", false);
        AutomationModuleDescriptor descriptor = new AutomationModuleDescriptor(
                AutomationStepType.RUN_PICKUP, "Pickup", "",
                List.of(new Parameter(
                        "timing", "Timing", "choice", false, List.of(),
                        List.of(new Choice("AFTER_ROUND", "After round"))
                ))
        );

        assertTrue(legacy.options().isEmpty());
        assertTrue(descriptor.validate(Map.of("timing", "AFTER_ROUND")).isEmpty());
        assertFalse(descriptor.validate(Map.of("timing", "INVALID")).isEmpty());
    }

    @Test
    void nonnegativeIntegerAcceptsZeroButRejectsNegativeAndFractionalValues() {
        AutomationModuleDescriptor descriptor = new AutomationModuleDescriptor(
                AutomationStepType.RUN_PICKUP, "Pickup", "",
                List.of(new Parameter("idle", "Idle", "nonnegative-integer", false))
        );

        assertTrue(descriptor.validate(Map.of("idle", "0")).isEmpty());
        assertFalse(descriptor.validate(Map.of("idle", "-1")).isEmpty());
        assertFalse(descriptor.validate(Map.of("idle", "1.5")).isEmpty());
    }
}
