package dev.stowling.schedule;

import java.util.ArrayList;
import java.util.List;

final class FakeRegionItemProbe implements RegionItemProbe {
    private final List<List<String>> observedRegions = new ArrayList<>();
    private Observation observation = Observation.EMPTY;

    void setObservation(Observation value) {
        observation = value;
    }

    List<List<String>> observedRegions() {
        return List.copyOf(observedRegions);
    }

    @Override
    public Observation observe(List<String> regionIds) {
        observedRegions.add(List.copyOf(regionIds));
        return observation;
    }
}
