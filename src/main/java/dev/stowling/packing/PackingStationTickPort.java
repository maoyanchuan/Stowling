package dev.stowling.packing;

import dev.stowling.domain.ContainerRef;
import java.util.Optional;

/**
 * Adds the tick-boundary operations required by the live packing driver.
 */
public interface PackingStationTickPort extends PackingStationPort {
    boolean prepare(PackingWorkcell workcell) throws PackingStationException;

    int takeConfirmedInsertion(PackingWorkcell workcell);

    void setInsertionSource(
            PackingWorkcell workcell,
            Optional<ContainerRef> source
    );

    boolean awaitingPackedBox(PackingWorkcell workcell);

    void clearAwaitingPackedBox(PackingWorkcell workcell);
}
