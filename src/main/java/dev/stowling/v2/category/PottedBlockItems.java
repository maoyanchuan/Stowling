package dev.stowling.v2.category;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PottedBlockItems {
    private static final String PREFIX = "potted_";
    private static final Map<String, String> ITEM_NAME_EXCEPTIONS = Map.of(
            "potted_azalea_bush", "azalea",
            "potted_flowering_azalea_bush", "flowering_azalea"
    );

    private PottedBlockItems() {
    }

    public static Optional<String> itemId(String blockId) {
        String normalized = Objects.requireNonNull(blockId, "blockId").trim();
        int separator = normalized.indexOf(':');
        String namespace = separator < 0
                ? "minecraft"
                : normalized.substring(0, separator);
        String path = separator < 0
                ? normalized
                : normalized.substring(separator + 1);
        if (!path.startsWith(PREFIX) || path.length() == PREFIX.length()) {
            return Optional.empty();
        }
        String itemPath = ITEM_NAME_EXCEPTIONS.getOrDefault(
                path,
                path.substring(PREFIX.length())
        );
        return Optional.of(namespace + ":" + itemPath);
    }
}
