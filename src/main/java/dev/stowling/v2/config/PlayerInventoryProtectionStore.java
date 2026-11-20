package dev.stowling.v2.config;

import com.google.gson.Gson;
import dev.stowling.automation.PlayerInventorySnapshot;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

public final class PlayerInventoryProtectionStore {
    private static final Gson GSON = new Gson();
    private final Path path;

    public PlayerInventoryProtectionStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public PlayerInventorySnapshot load() throws IOException {
        if (!Files.exists(path)) return new PlayerInventorySnapshot(Map.of());
        PlayerInventorySnapshot snapshot = GSON.fromJson(Files.readString(path),
                PlayerInventorySnapshot.class);
        if (snapshot == null) throw new IllegalArgumentException("背包锁定配置为空");
        return snapshot;
    }

    public void save(PlayerInventorySnapshot snapshot) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(snapshot));
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
