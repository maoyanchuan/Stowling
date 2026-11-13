package dev.stowling.client.interaction;

import java.util.List;
import java.util.Map;
import java.util.Objects;

final class ContainerQuickMovePlan {
    private ContainerQuickMovePlan() {
    }

    static int selectPlayerSlot(
            List<Candidate> candidates,
            Map<String, Integer> remaining
    ) {
        Objects.requireNonNull(candidates, "candidates");
        Objects.requireNonNull(remaining, "remaining");
        for (Candidate candidate : candidates) {
            int wanted = remaining.getOrDefault(candidate.itemId(), 0);
            if (candidate.count() <= wanted
                    && candidate.count() <= candidate.destinationCapacity()) {
                return candidate.slot();
            }
        }
        return -1;
    }

    record Candidate(
            int slot,
            String itemId,
            int count,
            int destinationCapacity
    ) {
        Candidate {
            itemId = Objects.requireNonNull(itemId, "itemId");
            if (slot < 0 || count <= 0 || destinationCapacity < 0) {
                throw new IllegalArgumentException("invalid quick move candidate");
            }
        }
    }
}
