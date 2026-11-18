package dev.stowling.automation;

import dev.stowling.domain.BlockPosition;
import dev.stowling.v2.model.AreaBounds;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;

public final class ContainerApproach {
    private final NavigationPort navigation;
    private final AreaBounds standingArea;
    private final Deque<BlockPosition> entrances;
    private final boolean multipleEntrances;
    private BlockPosition navigating;
    private BlockPosition reached;

    public ContainerApproach(NavigationPort navigation, AreaBounds standingArea,
            Collection<BlockPosition> entrances) {
        this.navigation = navigation;
        this.standingArea = standingArea;
        this.entrances = new ArrayDeque<>(entrances);
        multipleEntrances = entrances.size() > 1;
    }

    public NavigationStatus tick() {
        reached = null;
        if (multipleEntrances) {
            for (BlockPosition entrance : entrances) {
                if (navigation.isWithinInteractionRange(entrance, standingArea)) {
                    cancel();
                    reached = entrance;
                    return NavigationStatus.ARRIVED;
                }
            }
        }
        while (!entrances.isEmpty()) {
            BlockPosition entrance = entrances.getFirst();
            NavigationStatus status = navigating == null
                    ? navigation.startInteraction(entrance, standingArea)
                    : navigation.status(entrance, null);
            if (status == NavigationStatus.NAVIGATING) {
                navigating = entrance;
                return status;
            }
            navigating = null;
            if (status == NavigationStatus.ARRIVED && (standingArea == null && !multipleEntrances
                    || navigation.isWithinInteractionRange(entrance, standingArea))) {
                reached = entrance;
                return status;
            }
            entrances.removeFirst();
        }
        return NavigationStatus.FAILED;
    }

    public BlockPosition reached() {
        return reached;
    }

    public void cancel() {
        if (navigating != null) {
            navigation.cancel();
            navigating = null;
        }
    }
}
