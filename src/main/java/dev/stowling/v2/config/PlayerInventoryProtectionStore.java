package dev.stowling.v2.config;

import com.google.gson.Gson;
import dev.stowling.automation.PlayerInventorySnapshot;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class PlayerInventoryProtectionStore {
    private static final Gson GSON = new Gson();
    private final Path path;

    public PlayerInventoryProtectionStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public PlayerInventorySnapshot load() throws IOException {
        if (!Files.exists(path)) {
            return PlayerInventorySnapshot.locks(java.util.Set.of());
        }
        PlayerInventorySnapshot snapshot = GSON.fromJson(Files.readString(path),
                PlayerInventorySnapshot.class);
        if (snapshot == null) throw new IllegalArgumentException("背包锁定配置为空");
        if (snapshot.schemaVersion() > 2) {
            throw new IllegalArgumentException("不支持的背包锁定配置版本");
        }
        if (snapshot.schemaVersion() < 2) {
            Path backup = path.resolveSibling(path.getFileName() + ".v1.bak");
            if (!Files.exists(backup)) Files.copy(path, backup);
        }
        return snapshot;
    }

    public void save(PlayerInventorySnapshot snapshot) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.createDirectories(path.getParent());
            var json = new com.google.gson.JsonObject();
            json.addProperty("schemaVersion", 2);
            json.add("lockedSlots", GSON.toJsonTree(
                    snapshot.lockedSlots().stream().sorted().toList()));
            Files.writeString(temporary, GSON.toJson(json));
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("无法保存背包锁定配置", exception);
        }
    }
}
