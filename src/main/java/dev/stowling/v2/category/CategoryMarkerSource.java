package dev.stowling.v2.category;

import java.util.List;

public enum CategoryMarkerSource {
    PLACED_ITEM,
    ITEM_FRAME,
    SHELF,
    FLOWER_POT,
    CONTAINER_CONTENTS;

    public static final List<CategoryMarkerSource> SMART_SOURCES = List.of(
            PLACED_ITEM,
            ITEM_FRAME,
            SHELF,
            FLOWER_POT
    );
}
