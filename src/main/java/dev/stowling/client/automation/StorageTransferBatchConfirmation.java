package dev.stowling.client.automation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToIntFunction;
import java.util.function.ToLongFunction;

/** One acknowledgement barrier for a wave of exact-component keys, including different boxes. */
final class StorageTransferBatchConfirmation {
    record Before(int player, int container, long pickups) {
    }

    private final Map<String, Before> before;
    private final Map<String, StorageTransferConfirmation> confirmations;
    private final Map<String, Integer> planned;
    private final boolean withdraw;
    private final boolean emptyBoxSupply;

    StorageTransferBatchConfirmation(Map<String, Before> before, Map<String, Integer> moved,
            boolean withdraw, boolean emptyBoxSupply) {
        this.before = Map.copyOf(before);
        this.planned = Map.copyOf(moved);
        this.withdraw = withdraw;
        this.emptyBoxSupply = emptyBoxSupply;
        Map<String, StorageTransferConfirmation> result = new LinkedHashMap<>();
        moved.forEach((key, count) -> {
            Before counts = before.get(key);
            result.put(key, emptyBoxSupply
                    ? StorageTransferConfirmation.emptyBoxSupply(counts.player())
                    : StorageTransferConfirmation.exact(counts.player(), counts.container(),
                            withdraw ? count : -count));
        });
        confirmations = Map.copyOf(result);
    }

    boolean matches(ToIntFunction<String> player, ToIntFunction<String> container,
            ToLongFunction<String> pickups, boolean cursorEmpty) {
        return cursorEmpty && confirmations.entrySet().stream().allMatch(entry ->
                entry.getValue().matches(player.applyAsInt(entry.getKey()),
                        pickups.applyAsLong(entry.getKey()) - before.get(entry.getKey()).pickups(),
                        true, () -> container.applyAsInt(entry.getKey())));
    }

    boolean matchesServerContainer(ToIntFunction<String> container, boolean cursorEmpty) {
        return cursorEmpty && confirmations.entrySet().stream().allMatch(entry ->
                entry.getValue().matchesServerContainer(true, container.applyAsInt(entry.getKey())));
    }

    boolean hasContainerProgress(ToIntFunction<String> container) {
        return !emptyBoxSupply && before.entrySet().stream().anyMatch(entry ->
                (container.applyAsInt(entry.getKey()) - entry.getValue().container())
                        * (withdraw ? -1 : 1) > 0);
    }

    Optional<Map<String, Integer>> acceptedServerSnapshot(ToIntFunction<String> container,
            boolean cursorEmpty) {
        if (!cursorEmpty || emptyBoxSupply) return Optional.empty();
        Map<String, Integer> accepted = new LinkedHashMap<>();
        for (var entry : planned.entrySet()) {
            int delta = (container.applyAsInt(entry.getKey())
                    - before.get(entry.getKey()).container())
                    * (withdraw ? -1 : 1);
            if (delta < 0 || delta > entry.getValue()) return Optional.empty();
            if (delta > 0) accepted.put(entry.getKey(), delta);
        }
        return Optional.of(Map.copyOf(accepted));
    }
}
