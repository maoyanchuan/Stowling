package dev.stowling.automation;

import java.util.Objects;

public record ItemAmount(String itemId, int count) {
    public ItemAmount {
        Objects.requireNonNull(itemId, "itemId");
        if (itemId.isBlank()) {
            throw new IllegalArgumentException("itemId must not be blank");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
    }
}
