package dev.stowling.automation;

import java.util.Set;

public interface ContainerWithdrawalPort {
    ContainerWithdrawalResult startWithdrawal(
            ContainerTarget target,
            Set<String> matchingItemIds
    );

    ContainerWithdrawalResult pollWithdrawal();

    void cancelWithdrawal();
}
