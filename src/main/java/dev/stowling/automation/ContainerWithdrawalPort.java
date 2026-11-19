package dev.stowling.automation;

import java.util.Set;
import java.util.List;

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

    default ContainerWithdrawalResult startClassificationBatch(ContainerTarget target,
            Set<String> matchingItemIds, Set<String> fullBoxCategories, List<String> itemOrder) {
        return startClassificationBatch(target, matchingItemIds, fullBoxCategories);
    }

    default ContainerWithdrawalResult startClassificationBatch(ContainerTarget target,
            Set<String> matchingItemIds, Set<String> fullBoxCategories, List<String> itemOrder,
            int stackLimit) {
        return startClassificationBatch(target, matchingItemIds, fullBoxCategories, itemOrder);
    }

    void cancelWithdrawal();
}
