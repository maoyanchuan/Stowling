package dev.stowling.v2.runtime.modules;

import dev.stowling.automation.PlayerInventoryDelta;
import dev.stowling.automation.PlayerInventorySnapshot;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PlayerInventoryManager {
    @FunctionalInterface
    public interface Source {
        Optional<PlayerInventorySnapshot> snapshot();
    }

    private final Source source;
    private Optional<PlayerInventorySnapshot> previous = Optional.empty();

    public PlayerInventoryManager(Source source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    public synchronized Optional<Observation> observe() {
        Optional<PlayerInventorySnapshot> observed = source.snapshot();
        if (observed.isEmpty()) {
            return Optional.empty();
        }
        PlayerInventorySnapshot current = observed.orElseThrow();
        boolean baseline = previous.isEmpty();
        PlayerInventoryDelta delta = previous
                .map(prior -> PlayerInventoryDelta.between(prior, current))
                .orElseGet(() -> new PlayerInventoryDelta(Map.of(), Map.of()));
        previous = Optional.of(current);
        return Optional.of(new Observation(baseline, current, delta));
    }

    public synchronized void reset() {
        previous = Optional.empty();
    }

    public synchronized Optional<PlayerInventorySnapshot> current() {
        return previous;
    }

    public record Observation(
            boolean baseline,
            PlayerInventorySnapshot snapshot,
            PlayerInventoryDelta delta
    ) {
    }
}
