package dev.stowling.automation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AutomationModuleRegistry {
    private final Map<AutomationStepType, AutomationModule> modulesByType =
            new LinkedHashMap<>();
    private final Map<String, AutomationModule> modulesById =
            new LinkedHashMap<>();

    public void register(AutomationModule module) {
        Objects.requireNonNull(module, "module");
        AutomationModuleDescriptor descriptor = module.descriptor();
        AutomationStepType type = descriptor.type();
        if (modulesByType.containsKey(type)
                || modulesById.containsKey(descriptor.moduleId())) {
            throw new IllegalArgumentException("module already registered: " + type);
        }
        modulesByType.put(type, module);
        modulesById.put(descriptor.moduleId(), module);
    }

    public AutomationModule require(AutomationStepType type) {
        AutomationModule module = modulesByType.get(Objects.requireNonNull(type, "type"));
        if (module == null) {
            throw new IllegalArgumentException("unknown automation module: " + type);
        }
        return module;
    }

    public AutomationModule require(String moduleId) {
        String checkedId = requireText(moduleId, "moduleId");
        AutomationModule module = modulesById.get(checkedId);
        if (module == null) {
            throw new IllegalArgumentException(
                    "unknown automation module: " + checkedId
            );
        }
        return module;
    }

    public boolean contains(AutomationStepType type) {
        return modulesByType.containsKey(type);
    }

    public boolean contains(String moduleId) {
        return modulesById.containsKey(requireText(moduleId, "moduleId"));
    }

    public void validate(AutomationStep step) {
        Objects.requireNonNull(step, "step");
        require(step.type()).descriptor().requireValid(step.parameters());
    }

    public void validate(String moduleId, Map<String, String> parameters) {
        require(moduleId).descriptor().requireValid(parameters);
    }

    public List<AutomationModuleDescriptor> descriptors() {
        return modulesById.values().stream()
                .map(AutomationModule::descriptor)
                .toList();
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
