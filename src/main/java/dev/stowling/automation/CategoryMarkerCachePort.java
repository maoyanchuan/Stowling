package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface CategoryMarkerCachePort {
    Optional<Map<BlockPosition, Set<String>>> contents(UUID bindingId);

    void replaceBindings(
            Map<UUID, Map<BlockPosition, Set<String>>> contents
    );

    CategoryMarkerCachePort UNAVAILABLE =
            new CategoryMarkerCachePort() {
                @Override
                public Optional<Map<BlockPosition, Set<String>>> contents(
                        UUID bindingId
                ) {
                    return Optional.empty();
                }

                @Override
                public void replaceBindings(
                        Map<UUID, Map<BlockPosition, Set<String>>> contents
                ) {
                    throw new IllegalStateException("分类容器缓存组件不可用");
                }
            };
}
