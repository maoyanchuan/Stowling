package dev.stowling.automation;

import java.util.Map;

public interface ContainerTransferPort {
    ContainerTransferResult startDeposit(
            ContainerTarget target,
            Map<String, Integer> items
    );

    ContainerTransferResult pollDeposit();

    void cancelDeposit();
}
