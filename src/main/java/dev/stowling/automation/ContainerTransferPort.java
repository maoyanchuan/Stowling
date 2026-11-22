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

    ContainerTransferResult pollDeposit();

    void cancelDeposit();
}
