package dev.stowling.v2.model;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record ResourceReference(ResourceType type, String key) {
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-"
                    + "[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
    );
    private static final Set<String> OUTPUT_KEYS =
            Set.of("container", "storage", "marker");

    public ResourceReference {
        type = Objects.requireNonNull(type, "type");
        key = Objects.requireNonNull(key, "key").trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("resource key must not be blank");
        }
        if (type == ResourceType.OUTPUT) {
            key = key.toLowerCase(Locale.ROOT);
            if (!OUTPUT_KEYS.contains(key)) {
                throw new IllegalArgumentException("unknown output reference: " + key);
            }
        } else {
            UUID parsed = UUID.fromString(key);
            key = parsed.toString();
        }
    }

    public static ResourceReference parse(String value) {
        Objects.requireNonNull(value, "value");
        String trimmed = value.trim();
        int separator = trimmed.indexOf(':');
        if (separator <= 0 || separator == trimmed.length() - 1
                || trimmed.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("invalid resource reference");
        }
        String prefix = trimmed.substring(0, separator);
        String key = trimmed.substring(separator + 1);
        ResourceType type = switch (prefix) {
            case "coordinate" -> ResourceType.COORDINATE;
            case "region" -> ResourceType.REGION;
            case "category-zone" -> ResourceType.CATEGORY_ZONE;
            case "task" -> ResourceType.TASK;
            case "output" -> ResourceType.OUTPUT;
            default -> throw new IllegalArgumentException(
                    "unknown resource type: " + prefix
            );
        };
        if (type != ResourceType.OUTPUT && !UUID_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("resource key must be a UUID");
        }
        return new ResourceReference(type, key);
    }

    public static ResourceReference of(ResourceType type, UUID id) {
        Objects.requireNonNull(id, "id");
        return new ResourceReference(type, id.toString());
    }

    public static ResourceReference output(String key) {
        return new ResourceReference(ResourceType.OUTPUT, key);
    }

    public UUID uuid() {
        if (type == ResourceType.OUTPUT) {
            throw new IllegalStateException("output references do not have UUIDs");
        }
        return UUID.fromString(key);
    }

    public String encode() {
        return type.prefix() + ":" + key;
    }

    @Override
    public String toString() {
        return encode();
    }
}
