package dev.stowling.v2.scan;

import dev.stowling.v2.model.CategoryBindingDefinition;
import java.util.List;

public record CategoryScanResult(CategoryScanDefinition scan,
        List<CategoryBindingDefinition> bindings) {
    public CategoryScanResult {
        bindings = List.copyOf(bindings);
    }
}
