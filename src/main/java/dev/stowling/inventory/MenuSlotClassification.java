package dev.stowling.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record MenuSlotClassification(
        List<Integer> containerSlots,
        List<Integer> playerSlots
) {
    public MenuSlotClassification {
        containerSlots = List.copyOf(
                Objects.requireNonNull(containerSlots, "containerSlots")
        );
        playerSlots = List.copyOf(Objects.requireNonNull(playerSlots, "playerSlots"));
    }

    public static MenuSlotClassification classify(List<Boolean> playerSlotFlags) {
        Objects.requireNonNull(playerSlotFlags, "playerSlotFlags");
        List<Integer> containerSlots = new ArrayList<>();
        List<Integer> playerSlots = new ArrayList<>();
        for (int index = 0; index < playerSlotFlags.size(); index++) {
            Boolean playerSlot = Objects.requireNonNull(
                    playerSlotFlags.get(index),
                    "playerSlotFlags entry"
            );
            if (playerSlot) {
                playerSlots.add(index);
            } else {
                containerSlots.add(index);
            }
        }
        return new MenuSlotClassification(containerSlots, playerSlots);
    }

    public int containerCapacity() {
        return containerSlots.size();
    }

    public List<ContainerSlotMapping> logicalContainerSlots() {
        List<ContainerSlotMapping> mappings = new ArrayList<>();
        for (int logicalSlot = 0; logicalSlot < containerSlots.size(); logicalSlot++) {
            mappings.add(new ContainerSlotMapping(
                    logicalSlot,
                    containerSlots.get(logicalSlot)
            ));
        }
        return List.copyOf(mappings);
    }

    public record ContainerSlotMapping(int logicalSlot, int menuSlot) {
    }
}
