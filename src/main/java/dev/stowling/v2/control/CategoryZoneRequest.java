package dev.stowling.v2.control;

import java.util.List;

public record CategoryZoneRequest(
        String name,
        List<CategoryBindingRequest> bindings
) {
}
