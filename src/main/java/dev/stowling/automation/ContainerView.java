package dev.stowling.automation;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public record ContainerView(
        String containerId,
        String dimension,
        long revision,
        int slotCapacity,
        List<ContainerSlot> slots
) {
    public ContainerView {
        containerId = requireText(containerId, "containerId");
        dimension = requireText(dimension, "dimension");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must not be negative");
        }
        if (slotCapacity < 1) {
            throw new IllegalArgumentException("slotCapacity must be positive");
        }
        slots = List.copyOf(Objects.requireNonNull(slots, "slots"));
        Set<Integer> seenSlots = new HashSet<>();
        for (ContainerSlot slot : slots) {
            if (slot.slot() >= slotCapacity) {
                throw new IllegalArgumentException("slot index exceeds container capacity");
            }
            if (!seenSlots.add(slot.slot())) {
                throw new IllegalArgumentException("container slot is duplicated");
            }
        }
    }

    public boolean isEmpty() {
        return slots.isEmpty();
    }

    public int freeSlots() {
        return slotCapacity - slots.size();
    }

    public Map<String, Integer> itemCounts() {
        Map<String, Integer> counts = new HashMap<>();
        for (ContainerSlot slot : slots) {
            counts.merge(slot.itemId(), slot.count(), Integer::sum);
        }
        return Map.copyOf(counts);
    }

    public String slotFingerprint() {
        return slots.stream()
                .sorted(Comparator.comparingInt(ContainerSlot::slot))
                .map(slot -> slot.slot() + ":" + slot.itemId() + ":" + slot.count())
                .collect(Collectors.joining("|"));
    }

    private static String requireText(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName);
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }
}
