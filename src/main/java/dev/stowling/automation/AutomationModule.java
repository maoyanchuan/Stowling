package dev.stowling.automation;

public interface AutomationModule {
    AutomationModuleDescriptor descriptor();

    AutomationModuleResult execute(AutomationModuleContext context);
}
