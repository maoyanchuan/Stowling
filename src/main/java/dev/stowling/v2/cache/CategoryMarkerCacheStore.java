package dev.stowling.v2.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.stowling.automation.CategoryMarkerCachePort;
import dev.stowling.domain.BlockPosition;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class CategoryMarkerCacheStore
        implements CategoryMarkerCachePort {
    private static final int SCHEMA_VERSION = 1;
    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private final Path path;
    private final Map<UUID, CachedBinding> bindings;

    public CategoryMarkerCacheStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath()
                .normalize();
        this.bindings = load();
    }

    @Override
    public synchronized Optional<Map<BlockPosition, Set<String>>> contents(
            UUID bindingId
    ) {
        Objects.requireNonNull(bindingId, "bindingId");
        CachedBinding cached = bindings.get(bindingId);
        if (cached == null) {
            return Optional.empty();
        }
        Map<BlockPosition, Set<String>> result = new LinkedHashMap<>();
        for (CachedContainer container : cached.containers()) {
            result.put(
                    new BlockPosition(
                            container.x(),
                            container.y(),
                            container.z()
                    ),
                    Collections.unmodifiableSet(new LinkedHashSet<>(container.itemIds()))
            );
        }
        return Optional.of(Collections.unmodifiableMap(result));
    }

    @Override
    public synchronized void replaceBindings(
            Map<UUID, Map<BlockPosition, Set<String>>> contents
    ) {
        Objects.requireNonNull(contents, "contents");
        String updatedAt = Instant.now().toString();
        for (Map.Entry<UUID, Map<BlockPosition, Set<String>>> entry
                : contents.entrySet()) {
            List<CachedContainer> containers = new ArrayList<>();
            for (Map.Entry<BlockPosition, Set<String>> container
                    : entry.getValue().entrySet()) {
                BlockPosition position = container.getKey();
                containers.add(new CachedContainer(
                        position.x(),
                        position.y(),
                        position.z(),
                        List.copyOf(container.getValue())
                ));
            }
            bindings.put(
                    entry.getKey(),
                    new CachedBinding(
                            entry.getKey().toString(),
                            updatedAt,
                            List.copyOf(containers)
                    )
            );
        }
        write();
    }

    private Map<UUID, CachedBinding> load() {
        if (!Files.isRegularFile(path)) {
            return new LinkedHashMap<>();
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            CacheFile file = GSON.fromJson(json, CacheFile.class);
            if (file == null
                    || file.schemaVersion() != SCHEMA_VERSION
                    || file.bindings() == null) {
                return new LinkedHashMap<>();
            }
            Map<UUID, CachedBinding> result = new LinkedHashMap<>();
            for (CachedBinding binding : file.bindings()) {
                result.put(UUID.fromString(binding.bindingId()), binding);
            }
            return result;
        } catch (IOException | RuntimeException exception) {
            return new LinkedHashMap<>();
        }
    }

    private void write() {
        Path parent = path.getParent();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<CachedBinding> values = bindings.values().stream()
                    .sorted((left, right) ->
                            left.bindingId().compareTo(right.bindingId()))
                    .toList();
            CacheFile file = new CacheFile(SCHEMA_VERSION, values);
            Files.writeString(
                    temporary,
                    GSON.toJson(file) + System.lineSeparator(),
                    StandardCharsets.UTF_8
            );
            try {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        path,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "分类容器缓存写入失败",
                    exception
            );
        }
    }

    private record CacheFile(
            int schemaVersion,
            List<CachedBinding> bindings
    ) {
    }

    private record CachedBinding(
            String bindingId,
            String updatedAt,
            List<CachedContainer> containers
    ) {
    }

    private record CachedContainer(
            int x,
            int y,
            int z,
            List<String> itemIds
    ) {
    }
}
