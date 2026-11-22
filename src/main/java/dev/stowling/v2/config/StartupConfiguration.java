package dev.stowling.v2.config;

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
        ConfigStore configs = new ConfigStore(configPath);
        List<Issue> issues = new ArrayList<>();
        var storageSettings = new StorageSettingsStore();
        try {
            configs.loadOrReset();
        } catch (RuntimeException exception) {
            issues.add(new Issue(filePath(configPath),
                    V2ValidationException.diagnosticMessage(exception)));
        }
        return new StartupConfiguration(configs, storageSettings, issues);
    }

    private static String filePath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    public record Issue(String file, String detail) {
    }
}
