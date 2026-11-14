package dev.stowling.control;

import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationStepType;
import java.util.List;
import java.util.Objects;

public record AutomationModuleInfo(
        AutomationStepType type,
        String name,
        String description,
        List<AutomationModuleDescriptor.Parameter> parameters
) {
    public AutomationModuleInfo {
        type = Objects.requireNonNull(type, "type");
        name = Objects.requireNonNull(name, "name");
        description = Objects.requireNonNull(description, "description");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }

    public static AutomationModuleInfo from(AutomationModuleDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return new AutomationModuleInfo(
                descriptor.type(),
                descriptor.displayName(),
                descriptor.description(),
                descriptor.parameters()
        );
    }
}
