package dev.stowling.inventory;

/** Chest Tracker item lists can omit empty slots and cannot describe total capacity. */
public final class RecordedContainerCapacity {
    private RecordedContainerCapacity() {
    }

    public static int slots(String blockId, int positions) {
        if (positions < 1) {
            return 0;
        }
        if (blockId.equals("minecraft:chest") || blockId.equals("minecraft:trapped_chest")) {
            return 27 * Math.min(positions, 2);
        }
        if (blockId.equals("minecraft:barrel") || blockId.equals("minecraft:ender_chest")
                || blockId.equals("minecraft:shulker_box")
                || blockId.startsWith("minecraft:") && blockId.endsWith("_shulker_box")) {
            return 27;
        }
        return switch (blockId) {
            case "minecraft:hopper", "minecraft:brewing_stand" -> 5;
            case "minecraft:dispenser", "minecraft:dropper", "minecraft:crafter" -> 9;
            case "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker" -> 3;
            case "minecraft:chiseled_bookshelf" -> 6;
            default -> 0;
        };
    }
}
