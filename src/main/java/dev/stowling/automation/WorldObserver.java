package dev.stowling.automation;

import dev.stowling.domain.PickupSite;
import java.util.List;
import java.util.Set;

public interface WorldObserver {
    WorldObservation observeWorld();

    List<GroundItem> observeGroundItems(PickupSite site);

    Set<String> tagsFor(String itemId);
}
