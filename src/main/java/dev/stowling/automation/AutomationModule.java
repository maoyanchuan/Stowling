package dev.stowling.automation;

import dev.stowling.schedule.StopOutcome;

public interface AutomationModule {
    AutomationModuleDescriptor descriptor();

    AutomationModuleResult execute(AutomationModuleContext context);

    default StopOutcome stop(AutomationModuleContext context) {
        return StopOutcome.CLEAN;
    }

    default StopOutcome pollStop(AutomationModuleContext context) {
        return StopOutcome.CLEAN;
    }
}
