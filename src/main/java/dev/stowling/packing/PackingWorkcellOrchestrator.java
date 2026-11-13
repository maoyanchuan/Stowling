package dev.stowling.packing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PackingWorkcellOrchestrator {
    private final PackingStationPool stationPool;

    public PackingWorkcellOrchestrator(PackingStationPool stationPool) {
        this.stationPool = Objects.requireNonNull(stationPool, "stationPool");
    }

    public PackingRunResult pack(
            PackingRequest request,
            List<PackingStationCandidate> candidates,
            PackingStationPort port
    ) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(port, "port");

        List<PackingStationFailure> failures = new ArrayList<>();
        List<PackingPartialBox> partialBoxes = new ArrayList<>();
        Set<String> attemptedStations = new HashSet<>();
        int insertedQuantity = 0;

        while (insertedQuantity < request.quantity()) {
            List<PackingStationCandidate> remainingCandidates = candidates.stream()
                    .filter(candidate -> isNotAttempted(candidate, attemptedStations))
                    .toList();
            Optional<PackingStationReservation> selected = stationPool.reserve(
                    request.taskId(),
                    request.itemId(),
                    request.dimension(),
                    remainingCandidates
            );
            if (selected.isEmpty()) {
                break;
            }
            PackingStationReservation reservation = selected.get();
            attemptedStations.add(reservation.stationId());

            StationAttempt attempt;
            try {
                attempt = attemptStation(
                        request,
                        reservation.workcell(),
                        request.quantity() - insertedQuantity,
                        port
                );
            } catch (RuntimeException exception) {
                stationPool.releaseAndClearAssignment(reservation);
                throw exception;
            }
            release(reservation, attempt.keepAssignment());
            insertedQuantity += attempt.insertedQuantity();

            attempt.retainedContents().ifPresent(contents -> {
                if (!contents.itemCounts().isEmpty()) {
                    partialBoxes.add(new PackingPartialBox(
                            reservation.stationId(),
                            request.itemId(),
                            contents
                    ));
                }
            });

            if (attempt.kind() == AttemptKind.FULL) {
                return result(
                        PackingRunStatus.FULL_BOX_READY,
                        request,
                        insertedQuantity,
                        partialBoxes,
                        attempt.transferIntent(),
                        Optional.empty(),
                        failures
                );
            }
            attempt.failure().ifPresent(failures::add);
            if (attempt.kind() == AttemptKind.PARTIAL
                    && insertedQuantity == request.quantity()) {
                return result(
                        PackingRunStatus.PARTIAL_BOX,
                        request,
                        insertedQuantity,
                        partialBoxes,
                        Optional.empty(),
                        Optional.empty(),
                        failures
                );
            }
        }

        int remainingQuantity = request.quantity() - insertedQuantity;
        if (remainingQuantity > 0) {
            OverflowIntent overflow = new OverflowIntent(
                    request.itemId(),
                    remainingQuantity,
                    request.overflowContainer()
            );
            return result(
                    PackingRunStatus.OVERFLOW,
                    request,
                    insertedQuantity,
                    partialBoxes,
                    Optional.empty(),
                    Optional.of(overflow),
                    failures
            );
        }
        return result(
                PackingRunStatus.FAILED_RETAINED,
                request,
                insertedQuantity,
                partialBoxes,
                Optional.empty(),
                Optional.empty(),
                failures
        );
    }

    private StationAttempt attemptStation(
            PackingRequest request,
            PackingWorkcell workcell,
            int maximumQuantity,
            PackingStationPort port
    ) {
        PackingBoxContents before;
        try {
            before = port.inspect(workcell);
        } catch (PackingStationException exception) {
            return failure(
                    workcell.station().id(),
                    AttemptKind.FAILED,
                    0,
                    Optional.empty(),
                    PackingFailureStage.INSPECTION,
                    exception,
                    false
            );
        }
        if (!before.containsOnly(request.itemId())) {
            return new StationAttempt(
                    AttemptKind.FAILED,
                    0,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.of(new PackingStationFailure(
                            workcell.station().id(),
                            PackingFailureStage.INSPECTION,
                            "shulker contains more than one item id"
                    )),
                    true
            );
        }

        PackingThreshold threshold = workcell.threshold();
        int inserted = 0;
        PackingBoxContents after = before;
        if (!threshold.reached(before)) {
            try {
                inserted = port.insert(workcell, request.itemId(), maximumQuantity);
            } catch (PackingStationException exception) {
                return failure(
                        workcell.station().id(),
                        AttemptKind.FAILED,
                        0,
                        optionalNonEmpty(before),
                        PackingFailureStage.INSERTION,
                        exception,
                        !before.itemCounts().isEmpty()
                );
            }
            if (inserted < 0 || inserted > maximumQuantity) {
                return new StationAttempt(
                        AttemptKind.FAILED,
                        0,
                        optionalNonEmpty(before),
                        Optional.empty(),
                        Optional.of(new PackingStationFailure(
                                workcell.station().id(),
                                PackingFailureStage.INSERTION,
                                "insert result is outside the requested range"
                        )),
                        !before.itemCounts().isEmpty()
                );
            }
            try {
                after = port.inspect(workcell);
            } catch (PackingStationException exception) {
                return failure(
                        workcell.station().id(),
                        AttemptKind.FAILED,
                        0,
                        Optional.empty(),
                        PackingFailureStage.INSPECTION,
                        exception,
                        true
                );
            }
            if (!after.containsOnly(request.itemId())) {
                return new StationAttempt(
                        AttemptKind.FAILED,
                        0,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.of(new PackingStationFailure(
                                workcell.station().id(),
                                PackingFailureStage.INSPECTION,
                                "post-insert shulker contains multiple item ids"
                        )),
                        true
                );
            }
            long expectedTotal = before.totalCount() + inserted;
            if (after.totalCount() != expectedTotal) {
                return new StationAttempt(
                        AttemptKind.FAILED,
                        0,
                        optionalNonEmpty(after),
                        Optional.empty(),
                        Optional.of(new PackingStationFailure(
                                workcell.station().id(),
                                PackingFailureStage.INSPECTION,
                                "server slot counts did not confirm the insertion"
                        )),
                        true
                );
            }
            if (inserted == 0) {
                return new StationAttempt(
                        AttemptKind.FAILED,
                        0,
                        optionalNonEmpty(after),
                        Optional.empty(),
                        Optional.of(new PackingStationFailure(
                                workcell.station().id(),
                                PackingFailureStage.INSERTION,
                                "station accepted no items below its completion threshold"
                        )),
                        true
                );
            }
        }

        if (!threshold.reached(after)) {
            return new StationAttempt(
                    AttemptKind.PARTIAL,
                    inserted,
                    optionalNonEmpty(after),
                    Optional.empty(),
                    Optional.empty(),
                    true
            );
        }
        return finalizeFullBox(request, workcell, inserted, after, port);
    }

    private StationAttempt finalizeFullBox(
            PackingRequest request,
            PackingWorkcell workcell,
            int inserted,
            PackingBoxContents contents,
            PackingStationPort port
    ) {
        try {
            port.close(workcell);
        } catch (PackingStationException exception) {
            return failure(
                    workcell.station().id(),
                    AttemptKind.FAILED,
                    inserted,
                    optionalNonEmpty(contents),
                    PackingFailureStage.CLOSE,
                    exception,
                    true
            );
        }
        if (workcell.releaseMode() == PackingReleaseMode.EXPLICIT_INTERACT_BLOCK) {
            try {
                port.triggerBreak(workcell);
            } catch (PackingStationException exception) {
                return failure(
                        workcell.station().id(),
                        AttemptKind.FAILED,
                        inserted,
                        optionalNonEmpty(contents),
                        PackingFailureStage.BREAK_TRIGGER,
                        exception,
                        true
                );
            }
        }

        boolean arrived;
        try {
            arrived = port.awaitPackedBoxInProductContainer(
                    workcell,
                    request.itemId()
            );
        } catch (PackingStationException exception) {
            return failure(
                    workcell.station().id(),
                    AttemptKind.FAILED,
                    inserted,
                    optionalNonEmpty(contents),
                    PackingFailureStage.PRODUCT_CONFIRMATION,
                    exception,
                    true
            );
        }
        if (!arrived) {
            return new StationAttempt(
                    AttemptKind.FAILED,
                    inserted,
                    optionalNonEmpty(contents),
                    Optional.empty(),
                    Optional.of(new PackingStationFailure(
                            workcell.station().id(),
                            PackingFailureStage.PRODUCT_CONFIRMATION,
                            "packed box did not appear in the product container"
                    )),
                    true
            );
        }
        PackedBoxTransferIntent transfer = new PackedBoxTransferIntent(
                workcell.station().id(),
                request.itemId(),
                workcell.station().productContainer(),
                request.route().bulkContainer()
        );
        return new StationAttempt(
                AttemptKind.FULL,
                inserted,
                Optional.empty(),
                Optional.of(transfer),
                Optional.empty(),
                false
        );
    }

    private StationAttempt failure(
            String stationId,
            AttemptKind kind,
            int inserted,
            Optional<PackingBoxContents> retainedContents,
            PackingFailureStage stage,
            PackingStationException exception,
            boolean keepAssignment
    ) {
        String message = exception.getMessage();
        String reason = message == null || message.isBlank()
                ? exception.getClass().getSimpleName()
                : message;
        if (exception instanceof PackingStationTimeoutException) {
            reason = "timeout: " + reason;
        }
        return new StationAttempt(
                kind,
                inserted,
                retainedContents,
                Optional.empty(),
                Optional.of(new PackingStationFailure(
                        stationId,
                        stage,
                        reason
                )),
                keepAssignment
        );
    }

    private void release(PackingStationReservation reservation, boolean keepAssignment) {
        boolean released = keepAssignment
                ? stationPool.releaseKeepingAssignment(reservation)
                : stationPool.releaseAndClearAssignment(reservation);
        if (!released) {
            throw new IllegalStateException("packing station reservation was already released");
        }
    }

    private static Optional<PackingBoxContents> optionalNonEmpty(
            PackingBoxContents contents
    ) {
        return contents.itemCounts().isEmpty() ? Optional.empty() : Optional.of(contents);
    }

    private static boolean isNotAttempted(
            PackingStationCandidate candidate,
            Set<String> attemptedStations
    ) {
        return !attemptedStations.contains(candidate.workcell().station().id());
    }

    private static PackingRunResult result(
            PackingRunStatus status,
            PackingRequest request,
            int inserted,
            List<PackingPartialBox> partialBoxes,
            Optional<PackedBoxTransferIntent> transfer,
            Optional<OverflowIntent> overflow,
            List<PackingStationFailure> failures
    ) {
        int remaining = request.quantity() - inserted;
        return new PackingRunResult(
                status,
                request.taskId(),
                request.itemId(),
                request.quantity(),
                inserted,
                remaining,
                partialBoxes,
                transfer,
                overflow,
                failures
        );
    }

    private enum AttemptKind {
        FULL,
        PARTIAL,
        FAILED
    }

    private record StationAttempt(
            AttemptKind kind,
            int insertedQuantity,
            Optional<PackingBoxContents> retainedContents,
            Optional<PackedBoxTransferIntent> transferIntent,
            Optional<PackingStationFailure> failure,
            boolean keepAssignment
    ) {
    }
}
