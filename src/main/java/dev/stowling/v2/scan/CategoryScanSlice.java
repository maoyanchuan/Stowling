package dev.stowling.v2.scan;

import dev.stowling.domain.BlockPosition;
import java.util.Objects;

/** Empty reason means this entire slice passed; a failed slice produces no bindings. */
public record CategoryScanSlice(int index, BlockPosition origin, String reason) {
    public CategoryScanSlice {
        Objects.requireNonNull(origin, "origin");
        reason = reason == null ? "" : reason;
    }
}
