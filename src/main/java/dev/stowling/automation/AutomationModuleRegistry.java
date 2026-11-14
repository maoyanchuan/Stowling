package dev.stowling.automation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AutomationModuleRegistry {
    private final Map<AutomationStepType, AutomationModule> modules =
            new EnumMap<>(AutomationStepType.class);

    public void register(AutomationModule module) {
        Objects.requireNonNull(module, "module");
        AutomationStepType type = module.descriptor().type();
        AutomationModule previous = modules.putIfAbsent(type, module);
        if (previous != null) {
            throw new IllegalArgumentException("module already registered: " + type);
        }
    }

    public AutomationModule require(AutomationStepType type) {
        AutomationModule module = modules.get(Objects.requireNonNull(type, "type"));
        if (module == null) {
            throw new IllegalArgumentException("unknown automation module: " + type);
        }
        return module;
    }

    public boolean contains(AutomationStepType type) {
        return modules.containsKey(type);
    }

    public List<AutomationModuleDescriptor> descriptors() {
        return modules.keySet().stream()
                .map(type -> modules.get(type).descriptor())
                .toList();
    }
}
