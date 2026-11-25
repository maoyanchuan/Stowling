package dev.stowling.client.automation;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Tracks each exact item key in a wave while the container catches up with the clicks. */
final class DepositBatchConfirmation {
    private static final Duration IN_PLACE_STALL_TIMEOUT = Duration.ofSeconds(2);
    enum Mode { LIVE, SETTLEMENT, SERVER_SNAPSHOT }

    record Counts(int player, int container, long pickups) {
    }

    private Object batchMenu;
    private long expectedServerGeneration;

    void awaitServer(Object menu, long generation) {
        awaitServer(menu, generation, 1);
    }

    void awaitServer(Object menu, long generation, int snapshots) {
        if (snapshots <= 0) {
            throw new IllegalArgumentException("a server batch must expect at least one snapshot");
        }
        batchMenu = menu;
        // Each forced-state quick move produces one ordered full server response.
        expectedServerGeneration = Math.addExact(generation, snapshots);
    }

    boolean observeServer(Object menu, long generation, Function<String, Counts> counts) {
        if (menu != batchMenu || generation < expectedServerGeneration) return false;
        for (var entry : moves.entrySet()) {
            var move = entry.getValue();
            int delta = counts.apply(entry.getKey()).container() - move.before.container();
            if (delta < 0 || delta > move.count) {
                throw new IllegalStateException("服务器容器变化超出本批范围，停止存入以免错误记账");
            }
            move.observation = new DepositTransferPlan.TransferObservation(delta, 1, true);
        }
        return true;
    }

    private final Map<String, PendingMove> moves = new LinkedHashMap<>();

    void record(String key, int count, Function<String, Counts> before) {
        moves.computeIfAbsent(key, item -> new PendingMove(before.apply(item))).count += count;
    }

    boolean observe(Mode mode, Function<String, Counts> current) {
        boolean needsSnapshot = false;
        for (var entry : moves.entrySet()) {
            PendingMove move = entry.getValue();
            if (move.observation.stable()) continue;
            Counts now = current.apply(entry.getKey());
            int containerDelta = now.container() - move.before.container();
            int lastDelta = move.observation.delta();
            int polls = move.observation.observations();
            if (mode == Mode.SERVER_SNAPSHOT) {
                move.observation = DepositTransferPlan.observeServerSnapshot(move.count,
                        lastDelta, polls, containerDelta);
                continue;
            }
            int outgoing = DepositTransferPlan.outgoingCount(move.before.player(), now.player(),
                    move.before.pickups(), now.pickups());
            if (mode == Mode.LIVE) {
                needsSnapshot |= DepositTransferPlan.needsServerSnapshot(move.count, outgoing,
                        containerDelta);
                move.observation = DepositTransferPlan.observeConfirmation(move.count,
                        lastDelta, polls, outgoing, containerDelta);
            } else {
                move.observation = DepositTransferPlan.observeSettlement(move.count,
                        lastDelta, polls, outgoing, containerDelta);
            }
        }
        return needsSnapshot;
    }

    void resetUnsettled() {
        for (PendingMove move : moves.values()) {
            if (!move.observation.stable()) move.observation = PendingMove.UNOBSERVED;
        }
    }

    boolean settled() {
        return moves.values().stream().allMatch(move -> move.observation.stable());
    }

    boolean fullyAccepted() {
        return moves.values().stream().allMatch(move -> move.observation.stable()
                && move.observation.delta() == move.count);
    }

    boolean stalled(Duration waiting) {
        return !settled() && waiting.compareTo(IN_PLACE_STALL_TIMEOUT) >= 0;
    }

    Map<String, Integer> accepted() {
        Map<String, Integer> accepted = new LinkedHashMap<>();
        moves.forEach((key, move) -> {
            if (move.observation.stable() && move.observation.delta() > 0) {
                accepted.put(key, move.observation.delta());
            }
        });
        return accepted;
    }

    String describe(Function<String, Counts> current) {
        return moves.entrySet().stream().map(entry -> {
            PendingMove move = entry.getValue();
            return entry.getKey() + " planned=" + move.count + " before=" + move.before
                    + " now=" + current.apply(entry.getKey()) + " observed=" + move.observation;
        }).toList().toString();
    }

    private static final class PendingMove {
        private static final DepositTransferPlan.TransferObservation UNOBSERVED =
                new DepositTransferPlan.TransferObservation(-1, 0, false);

        private final Counts before;
        private int count;
        private DepositTransferPlan.TransferObservation observation = UNOBSERVED;

        private PendingMove(Counts before) {
            this.before = before;
        }
    }
}
