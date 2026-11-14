package dev.stowling.schedule;

import dev.stowling.domain.PickupRegion;
import java.util.List;

public interface RegionItemProbe {
    enum Observation {
        PRESENT,
        EMPTY,
        UNOBSERVABLE
    }

    Observation observe(List<String> regionIds);

    default Observation observeRegions(List<PickupRegion> regions) {
        return Observation.UNOBSERVABLE;
    }
}
