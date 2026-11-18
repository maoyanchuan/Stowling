package dev.stowling.v2.model;

public record StoragePolicy(
        StorageMode mode,
        int loosePercent,
        boolean allowPartialBoxes,
        AreaBounds packingArea,
        boolean refillLoose,
        Integer looseSlots,
        String partialBoxDestinationRef
) {
    public StoragePolicy(StorageMode mode, int loosePercent, boolean allowPartialBoxes,
            AreaBounds packingArea, boolean refillLoose) {
        this(mode, loosePercent, allowPartialBoxes, packingArea, refillLoose, null, null);
    }

    public static final StoragePolicy DEFAULT =
            new StoragePolicy(StorageMode.NORMAL, 50, false, null);

    public StoragePolicy {
        mode = mode == null ? StorageMode.NORMAL : mode;
        allowPartialBoxes = mode == StorageMode.BOX_ONLY || allowPartialBoxes;
        if (loosePercent < 0 || loosePercent > 100) {
            throw new IllegalArgumentException("散装比例必须在 0 到 100 之间");
        }
        if (looseSlots != null && (looseSlots < 0 || looseSlots > 54)) {
            throw new IllegalArgumentException("散装槽位必须为 0 到 54 格");
        }
        if (partialBoxDestinationRef == null || partialBoxDestinationRef.isBlank()) {
            partialBoxDestinationRef = null;
        } else {
            ResourceReference ref = ResourceReference.parse(partialBoxDestinationRef);
            if (ref.type() != ResourceType.COORDINATE && ref.type() != ResourceType.REGION) {
                throw new IllegalArgumentException("未满盒位置必须引用坐标或区域");
            }
            partialBoxDestinationRef = ref.encode();
        }
    }

    public StoragePolicy(StorageMode mode, int loosePercent, boolean allowPartialBoxes,
            AreaBounds packingArea) {
        this(mode, loosePercent, allowPartialBoxes, packingArea, false);
    }

    public StoragePolicy shift(int x, int z) {
        return new StoragePolicy(mode, loosePercent, allowPartialBoxes,
                packingArea == null ? null : packingArea.shift(x, 0, z), refillLoose,
                looseSlots, partialBoxDestinationRef);
    }
}
