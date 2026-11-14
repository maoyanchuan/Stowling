package dev.stowling.schedule;

import java.util.List;

public interface RegionItemProbe {
    enum Observation {
        PRESENT,
        EMPTY,
        UNOBSERVABLE
    }

    Observation observe(List<String> regionIds);
}
