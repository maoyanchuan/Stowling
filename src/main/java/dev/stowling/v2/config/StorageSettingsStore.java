package dev.stowling.v2.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.stowling.v2.model.StorageAutomationSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Global settings are separate from task definitions and retain their own revision. */
public final class StorageSettingsStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;
    private StorageAutomationSettings settings;
    private long revision;
    private ConfigStore coordinates;

    public synchronized void bindCoordinates(ConfigStore coordinates) {
        this.coordinates = coordinates;
        var migrated = CoordinateResourceMigration.migrate(coordinates, settings);
        if (!migrated.equals(settings)) {
            save(Long.toString(revision), migrated);
        }
    }

    public synchronized StorageAutomationSettings resolved() {
        if (coordinates == null) {
            return settings;
        }
        var points = coordinates.read().config().coordinates();
        java.util.function.Function<java.util.UUID, dev.stowling.domain.BlockPosition> resolve = id -> {
            var point = points.stream().filter(value -> value.id().equals(id)).findFirst()
                    .orElseThrow(() -> new IllegalStateException("公共设施引用的坐标不存在"));
            return new dev.stowling.domain.BlockPosition(point.x(), point.y(), point.z());
        };
        return new StorageAutomationSettings(settings.cleanupEnabled(),
                settings.temporaryCoordinateIds().stream().map(resolve).toList(),
                settings.emptyBoxCoordinateIds().stream().map(resolve).toList(),
                settings.fallbackCoordinateId() == null ? null
                        : resolve.apply(settings.fallbackCoordinateId()),
                java.util.List.of(), java.util.List.of(), null, settings.temporaryRegionIds());
    }

    public synchronized java.util.List<dev.stowling.v2.model.RegionDefinition> temporaryRegions() {
        if (coordinates == null) {
            return java.util.List.of();
        }
        var regions = coordinates.read().config().regions();
        return settings.temporaryRegionIds().stream().map(id -> regions.stream()
                .filter(region -> region.id().equals(id)).findFirst().orElseThrow(() ->
                        new IllegalStateException("暂存设施引用的区域不存在"))).toList();
    }

    public StorageSettingsStore(Path path) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        settings = Files.exists(path)
                ? GSON.fromJson(Files.readString(path), StorageAutomationSettings.class)
                : StorageAutomationSettings.DEFAULT;
        if (settings == null) {
            throw new IllegalArgumentException("全局储存配置为空，原文件已保留");
        }
    }

    public synchronized Snapshot read() {
        return new Snapshot(Long.toString(revision), settings);
    }

    public synchronized Snapshot save(String expected, StorageAutomationSettings value) {
        if (!Long.toString(revision).equals(expected)) {
            throw new IllegalArgumentException("全局配置已变化，请刷新后重试");
        }
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(value), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存全局储存配置", exception);
        }
        settings = value;
        revision++;
        return read();
    }

    public record Snapshot(String revision, StorageAutomationSettings settings) {
    }
}
