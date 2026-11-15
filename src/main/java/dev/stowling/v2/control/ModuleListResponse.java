package dev.stowling.v2.control;

import dev.stowling.v2.model.ModuleDefinition;
import java.util.List;

public record ModuleListResponse(List<ModuleDefinition> modules) {
    public ModuleListResponse {
        modules = List.copyOf(modules);
    }
}
