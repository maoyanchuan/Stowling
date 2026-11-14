package dev.stowling.control;

import dev.stowling.automation.AutomationModuleDescriptor;
import dev.stowling.automation.AutomationStepType;
import java.util.List;
import java.util.Objects;

public record AutomationModuleInfo(
        AutomationStepType type,
        String name,
        String description,
        List<AutomationModuleDescriptor.Parameter> parameters,
        String moduleId
) {
    public AutomationModuleInfo {
        moduleId = Objects.requireNonNull(moduleId, "moduleId");
        type = Objects.requireNonNull(type, "type");
        name = Objects.requireNonNull(name, "name");
        description = Objects.requireNonNull(description, "description");
        parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }

    public AutomationModuleInfo(
            AutomationStepType type,
            String name,
            String description,
            List<AutomationModuleDescriptor.Parameter> parameters
    ) {
        this(type, name, description, parameters, type.name());
    }

    public static AutomationModuleInfo from(AutomationModuleDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        return new AutomationModuleInfo(
                descriptor.type(),
                descriptor.displayName(),
                descriptor.description(),
                descriptor.parameters(),
                descriptor.moduleId()
        );
    }
}
