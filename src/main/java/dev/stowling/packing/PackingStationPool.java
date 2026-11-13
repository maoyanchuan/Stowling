package dev.stowling.packing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class PackingStationPool {
    private final Map<String, StationState> states = new HashMap<>();
    private long nextSequence = 1L;

    public synchronized Optional<PackingStationReservation> reserve(
            String taskId,
            String itemId,
            String dimension,
            List<PackingStationCandidate> candidates
    ) {
        String checkedTaskId = PackingChecks.requireText(taskId, "taskId");
        String checkedItemId = PackingChecks.requireText(itemId, "itemId");
        String checkedDimension = PackingChecks.requireText(dimension, "dimension");
        Objects.requireNonNull(candidates, "candidates");

        Map<String, PackingStationCandidate> candidatesById = uniqueCandidates(candidates);
        List<PackingStationCandidate> selectable = new ArrayList<>();
        for (PackingStationCandidate candidate : candidatesById.values()) {
            if (!candidate.reachable() || !candidate.operational()) {
                continue;
            }
            if (!checkedDimension.equals(candidate.workcell().station().dimension())) {
                continue;
            }
            StationState state = states.get(candidate.workcell().station().id());
            if (state == null) {
                selectable.add(candidate);
                continue;
            }
            boolean compatibleAssignment = state.assignedItemId == null
                    || checkedItemId.equals(state.assignedItemId);
            if (state.reservedTaskId == null && compatibleAssignment) {
                selectable.add(candidate);
            }
        }

        selectable.sort(selectionOrder(checkedItemId));
        if (selectable.isEmpty()) {
            return Optional.empty();
        }

        PackingStationCandidate selected = selectable.getFirst();
        String stationId = selected.workcell().station().id();
        StationState state = states.computeIfAbsent(stationId, ignored -> new StationState());
        state.assignedItemId = checkedItemId;
        state.reservedTaskId = checkedTaskId;
        state.sequence = nextSequence;
        nextSequence++;
        return Optional.of(new PackingStationReservation(
                checkedTaskId,
                checkedItemId,
                selected.workcell(),
                selected.distanceSquared(),
                state.sequence
        ));
    }

    public synchronized boolean releaseKeepingAssignment(
            PackingStationReservation reservation
    ) {
        return release(reservation, true);
    }

    public synchronized boolean releaseAndClearAssignment(
            PackingStationReservation reservation
    ) {
        return release(reservation, false);
    }

    public synchronized boolean isReserved(String stationId) {
        String checkedStationId = PackingChecks.requireText(stationId, "stationId");
        StationState state = states.get(checkedStationId);
        return state != null && state.reservedTaskId != null;
    }

    public synchronized Optional<String> assignedItemId(String stationId) {
        String checkedStationId = PackingChecks.requireText(stationId, "stationId");
        StationState state = states.get(checkedStationId);
        if (state == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(state.assignedItemId);
    }

    private boolean release(PackingStationReservation reservation, boolean keepAssignment) {
        Objects.requireNonNull(reservation, "reservation");
        StationState state = states.get(reservation.stationId());
        if (state == null
                || state.sequence != reservation.sequence()
                || !reservation.taskId().equals(state.reservedTaskId)) {
            return false;
        }
        state.reservedTaskId = null;
        if (!keepAssignment) {
            state.assignedItemId = null;
        }
        return true;
    }

    private Comparator<PackingStationCandidate> selectionOrder(String itemId) {
        return Comparator
                .comparingInt((PackingStationCandidate candidate) -> {
                    StationState state = states.get(candidate.workcell().station().id());
                    return state != null && itemId.equals(state.assignedItemId) ? 0 : 1;
                })
                .thenComparingLong(PackingStationCandidate::distanceSquared)
                .thenComparing(candidate -> candidate.workcell().station().id());
    }

    private static Map<String, PackingStationCandidate> uniqueCandidates(
            List<PackingStationCandidate> candidates
    ) {
        Map<String, PackingStationCandidate> unique = new HashMap<>();
        for (PackingStationCandidate candidate : candidates) {
            Objects.requireNonNull(candidate, "candidate");
            String stationId = candidate.workcell().station().id();
            if (unique.putIfAbsent(stationId, candidate) != null) {
                throw new IllegalArgumentException("duplicate packing station id: " + stationId);
            }
        }
        return unique;
    }

    private static final class StationState {
        private String assignedItemId;
        private String reservedTaskId;
        private long sequence;
    }
}
