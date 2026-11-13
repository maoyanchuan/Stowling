package dev.stowling.packing;

public interface PackingStationPort {
    PackingBoxContents inspect(PackingWorkcell workcell) throws PackingStationException;

    int insert(
            PackingWorkcell workcell,
            String itemId,
            int maximumQuantity
    ) throws PackingStationException;

    void close(PackingWorkcell workcell) throws PackingStationException;

    void triggerBreak(PackingWorkcell workcell) throws PackingStationException;

    boolean awaitPackedBoxInProductContainer(
            PackingWorkcell workcell,
            String itemId
    ) throws PackingStationException;
}
