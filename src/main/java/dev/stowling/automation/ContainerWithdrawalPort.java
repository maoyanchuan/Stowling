package dev.stowling.automation;

import java.util.Set;

public interface ContainerWithdrawalPort {
    ContainerWithdrawalResult startWithdrawal(
            ContainerTarget target,
            Set<String> matchingItemIds
    );

    ContainerWithdrawalResult pollWithdrawal();

    default ContainerWithdrawalResult startClassificationBatch(
            ContainerTarget target, Set<String> matchingItemIds) {
        return startWithdrawal(target, matchingItemIds);
    }

    default ContainerWithdrawalResult startClassificationBatch(ContainerTarget target,
            Set<String> matchingItemIds, Set<String> fullBoxCategories) {
        return startClassificationBatch(target, matchingItemIds);
    }

    void cancelWithdrawal();
}
