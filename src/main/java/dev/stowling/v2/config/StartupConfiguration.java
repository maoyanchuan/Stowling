package dev.stowling.v2.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record StartupConfiguration(
        ConfigStore configs,
        StorageSettingsStore storageSettings,
        List<Issue> issues
) {
    public StartupConfiguration {
        issues = List.copyOf(issues);
    }

    public static StartupConfiguration load(Path directory) {
        Path configPath = directory.resolve("stowling.json");
        Path storagePath = directory.resolve("stowling-storage-settings.json");
        ConfigStore configs = new ConfigStore(configPath);
        List<Issue> issues = new ArrayList<>();
        StorageSettingsStore storageSettings = null;
        try {
            configs.loadOrReset();
        } catch (RuntimeException exception) {
            issues.add(new Issue(filePath(configPath),
                    V2ValidationException.diagnosticMessage(exception)));
        }
        try {
            storageSettings = new StorageSettingsStore(storagePath);
        } catch (IOException exception) {
            issues.add(new Issue(filePath(storagePath), "无法读取文件，请检查文件和读取权限。"));
        } catch (RuntimeException exception) {
            issues.add(new Issue(filePath(storagePath),
                    V2ValidationException.diagnosticMessage(exception)));
        }
        if (issues.isEmpty()) {
            try {
                storageSettings.bindCoordinates(configs);
            } catch (RuntimeException exception) {
                issues.add(new Issue(
                        filePath(configPath) + " / " + filePath(storagePath),
                        "公共设施坐标迁移失败。 " + V2ValidationException.diagnosticMessage(exception)
                ));
            }
        }
        return new StartupConfiguration(configs, storageSettings, issues);
    }

    private static String filePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    public record Issue(String file, String detail) {
    }
}
