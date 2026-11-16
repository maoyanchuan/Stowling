package dev.stowling.v2.runtime.modules;

import dev.stowling.domain.BlockPosition;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class MarkerCacheSaveAction {
    private MarkerCacheSaveAction() {
    }

    static void save(
            TaskExecutionContext context,
            Map<UUID, Map<BlockPosition, Set<String>>> cache
    ) {
        context.categoryMarkerCache().replaceBindings(cache);
    }
}
