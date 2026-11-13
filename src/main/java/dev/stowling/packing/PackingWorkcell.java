package dev.stowling.packing;

import dev.stowling.domain.PackingStation;
import java.util.Objects;

public record PackingWorkcell(PackingStation station, PackingReleaseMode releaseMode) {
    public PackingWorkcell {
        station = Objects.requireNonNull(station, "station");
        releaseMode = Objects.requireNonNull(releaseMode, "releaseMode");
    }

    public PackingThreshold threshold() {
        return PackingThreshold.from(station);
    }
}
