package dev.stowling.v2.cache;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.stowling.domain.BlockPosition;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The robot's index of boxes it has deposited in warehouse containers. */
public final class RobotWarehouseCache {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private Map<Location, Map<String, Box>> contents;
    private boolean dirty;

    public RobotWarehouseCache(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public synchronized void prepare() {
        if (contents == null) contents = load();
        if (dirty) write();
    }

    public synchronized Map<String, Box> boxes(Location location) {
        prepare();
        return Map.copyOf(contents.getOrDefault(location, Map.of()));
    }

    public synchronized void stored(Location location, String fingerprint,
            Map<String, Integer> items) {
        Map<String, Box> boxes = new LinkedHashMap<>(boxes(location));
        Box previous = boxes.get(fingerprint);
        boxes.put(fingerprint, new Box(items, previous == null ? 1 : previous.count() + 1));
        update(location, boxes);
    }

    public synchronized void withdrawn(Location location, String fingerprint) {
        Map<String, Box> boxes = new LinkedHashMap<>(boxes(location));
        Box previous = boxes.get(fingerprint);
        if (previous == null) return;
        if (previous.count() == 1) {
            boxes.remove(fingerprint);
        } else {
            boxes.put(fingerprint, new Box(previous.items(), previous.count() - 1));
        }
        update(location, boxes);
    }

    public synchronized void reconcile(Location location, Map<String, Integer> observed) {
        Map<String, Box> indexed = boxes(location);
        Map<String, Box> retained = new LinkedHashMap<>();
        indexed.forEach((fingerprint, box) -> {
            int count = Math.min(box.count(), observed.getOrDefault(fingerprint, 0));
            if (count > 0) retained.put(fingerprint, new Box(box.items(), count));
        });
        if (!retained.equals(indexed)) update(location, retained);
    }

    private void update(Location location, Map<String, Box> boxes) {
        if (boxes.isEmpty()) {
            contents.remove(location);
        } else {
            contents.put(location, boxes);
        }
        dirty = true;
        write();
    }

    private Map<Location, Map<String, Box>> load() {
        Map<Location, Map<String, Box>> loaded = new LinkedHashMap<>();
        if (Files.notExists(path)) return loaded;
        try {
            CacheFile file = GSON.fromJson(Files.readString(path), CacheFile.class);
            if (file == null || file.version() != 1) {
                throw new IllegalStateException("无法识别机器人仓库缓存版本");
            }
            for (CachedContainer container : file.containers()) {
                loaded.put(container.location(), Map.copyOf(container.boxes()));
            }
            return loaded;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("机器人仓库缓存读取失败", exception);
        }
    }

    private void write() {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        var containers = contents.entrySet().stream()
                .map(entry -> new CachedContainer(entry.getKey(), entry.getValue())).toList();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(temporary, GSON.toJson(new CacheFile(1, containers)));
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } catch (IOException exception) {
            throw new IllegalStateException("机器人仓库缓存保存失败", exception);
        }
    }

    public record Location(String scope, BlockPosition position) {
    }

    public record Box(Map<String, Integer> items, int count) {
        public Box {
            items = Map.copyOf(items);
        }
    }

    private record CachedContainer(Location location, Map<String, Box> boxes) {
    }

    private record CacheFile(int version, List<CachedContainer> containers) {
    }
}
