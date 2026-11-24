package dev.stowling.automation;

import java.util.Map;

public interface ContainerTransferPort {
    ContainerTransferResult startDeposit(
            ContainerTarget target,
            Map<String, Integer> items
    );

    default ContainerTransferResult startCollectedCacheDeposit(
            ContainerTarget target, Map<String, Integer> items) {
        return startDeposit(target, items);
    }

    /** Allows consecutive fixed batches to reuse the same open pickup cache. */
    default ContainerTransferResult startPickupCacheDeposit(ContainerTarget target,
            Map<String, Integer> items) {
        return startCollectedCacheDeposit(target, items);
    }

    default void finishPickupCacheDeposit() {
        cancelDeposit();
    }

    /** Reads capacity from the confirmed open cache, without opening another container. */
    default boolean isPickupCacheFull(ContainerTarget target) {
        return false;
    }

    ContainerTransferResult pollDeposit();

    void cancelDeposit();
}
