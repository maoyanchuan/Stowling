package dev.stowling.automation;

import dev.stowling.domain.PickupRegion;
import dev.stowling.domain.PickupSite;
import dev.stowling.domain.ContainerRef;
import dev.stowling.domain.ContainerType;
import java.util.List;
import java.util.Set;

public interface WorldObserver {
    WorldObservation observeWorld();

    List<GroundItem> observeGroundItems(PickupSite site);

    default List<GroundItem> observeGroundItems(PickupRegion region) {
        return observeGroundItems(new PickupSite(
                region.id(),
                region.dimension(),
                region.standbyPosition(),
                1,
                List.of(new ContainerRef(
                        "observer-adapter",
                        region.dimension(),
                        region.standbyPosition(),
                        ContainerType.CHEST
                )),
                java.time.Duration.ZERO
        ));
    }

    Set<String> tagsFor(String itemId);
}
