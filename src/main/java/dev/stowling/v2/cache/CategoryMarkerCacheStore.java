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
    private final Map<UUID, CachedBinding> bindings = new LinkedHashMap<>();
    private final Map<String, Visibility> markerVisibility = new LinkedHashMap<>();

    public CategoryMarkerCacheStore(Path path) {
        this.path = Objects.requireNonNull(path, "path").toAbsolutePath()
                .normalize();
        load();
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
        updateRefresh(contents, null, Set.of(), Map.of());
    }

    public synchronized Visibility visibility(String scope) {
        return markerVisibility.getOrDefault(scope, Visibility.EMPTY);
    }

    @Override
    public synchronized void updateRefresh(Map<UUID, Map<BlockPosition, Set<String>>> contents,
            String scope, Set<BlockPosition> markerPositions,
            Map<BlockPosition, Boolean> inventoryVisibility) {
        Objects.requireNonNull(contents, "contents");
        if (contents.isEmpty() && markerPositions.isEmpty() && inventoryVisibility.isEmpty()) {
            return;
        }
        Map<UUID, CachedBinding> updatedBindings = new LinkedHashMap<>(bindings);
        Map<String, Visibility> updatedVisibility = new LinkedHashMap<>(markerVisibility);
        if (scope != null && !markerPositions.isEmpty()) {
            Visibility previous = visibility(scope);
            Set<BlockPosition> known = new LinkedHashSet<>(previous.known());
            known.addAll(markerPositions);
            Set<BlockPosition> updated = new LinkedHashSet<>(previous.visible());
            inventoryVisibility.forEach((position, visible) -> {
                if (visible) {
                    updated.add(position);
                } else {
                    updated.remove(position);
                }
            });
            updatedVisibility.put(scope, new Visibility(known, updated));
        }
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
            updatedBindings.put(
                    entry.getKey(),
                    new CachedBinding(
                            entry.getKey().toString(),
                            updatedAt,
                            List.copyOf(containers)
                    )
            );
        }
        write(updatedBindings, updatedVisibility);
        bindings.clear();
        bindings.putAll(updatedBindings);
        markerVisibility.clear();
        markerVisibility.putAll(updatedVisibility);
    }

    private void load() {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            String json = Files.readString(path, StandardCharsets.UTF_8);
            CacheFile file = GSON.fromJson(json, CacheFile.class);
            if (file == null
                    || file.schemaVersion() != SCHEMA_VERSION
                    || file.bindings() == null) {
                return;
            }
            for (CachedBinding binding : file.bindings()) {
                bindings.put(UUID.fromString(binding.bindingId()), binding);
            }
            if (file.markerVisibility() != null) {
                markerVisibility.putAll(file.markerVisibility());
            }
        } catch (IOException | RuntimeException exception) {
            bindings.clear();
            markerVisibility.clear();
        }
    }

    private void write(Map<UUID, CachedBinding> updatedBindings,
            Map<String, Visibility> updatedVisibility) {
        Path parent = path.getParent();
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<CachedBinding> values = updatedBindings.values().stream()
                    .sorted((left, right) ->
                            left.bindingId().compareTo(right.bindingId()))
                    .toList();
            CacheFile file = new CacheFile(SCHEMA_VERSION, values, updatedVisibility);
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

    public record Visibility(Set<BlockPosition> known, Set<BlockPosition> visible) {
        public static final Visibility EMPTY = new Visibility(Set.of(), Set.of());

        public Visibility {
            known = Set.copyOf(known);
            visible = Set.copyOf(visible);
        }
    }

    private record CacheFile(
            int schemaVersion,
            List<CachedBinding> bindings,
            Map<String, Visibility> markerVisibility
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
