package dev.stowling.automation;

import java.util.List;

/** Opening a blocked container must never fall through to using a carried building material. */
public final class ContainerOpenHandPlan {
    private ContainerOpenHandPlan() {
    }

    public static int select(int selected, List<Boolean> safeSlots) {
        if (selected >= 0 && selected < safeSlots.size() && safeSlots.get(selected)) return selected;
        for (int index = 0; index < safeSlots.size(); index++) {
            if (safeSlots.get(index)) return index;
        }
        return -1;
    }
}
